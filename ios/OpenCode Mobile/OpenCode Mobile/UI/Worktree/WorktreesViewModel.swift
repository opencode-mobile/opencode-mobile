import Foundation
import Observation

/// One worktree row: never the main checkout.
struct WorktreeRowItem: Identifiable, Equatable {
    var id: String { directory }
    let projectKey: String
    let directory: String
    /// Branch, else folder name.
    let name: String
    let sessionCount: Int
    let isRunning: Bool
    /// Uncommitted changes the server reported; nil until known or when it could not tell.
    let changeCount: Int?
    let isRemoving: Bool
}

/// A project and its worktrees, under its main checkout.
struct WorktreesGroupItem: Identifiable, Equatable {
    var id: String { projectKey }
    let projectKey: String
    let displayName: String
    /// Added path that colours the project badge.
    let primaryPath: String
    let mainCheckout: String
    let worktrees: [WorktreeRowItem]
}

/// Bottom sheet of a worktree's actions.
struct WorktreeActionsItem: Equatable {
    let projectKey: String
    let directory: String
    let name: String
    let projectName: String
    let sessionCount: Int
    /// Home shows worktrees, so its scope picker can select this one.
    let canShowOnHome: Bool
}

enum WorktreeRemoveDialog: Equatable {
    /// Remove, or Remove anyway when uncommitted work may be lost: `changeCount` changes,
    /// `changesUnknown` (the server refused without force and gave no count), or `changesUnchecked`
    /// (the change count could not be read, so the screen cannot say).
    case confirm(
        directory: String,
        name: String,
        changeCount: Int?,
        changesUnknown: Bool,
        sessionCount: Int,
        changesUnchecked: Bool = false
    )
    /// A session runs in the worktree: stop it first.
    case blocked(name: String, session: SessionListItem)

    /// Known to lose changes, so a v2 remove forces.
    var losesChanges: Bool {
        guard case let .confirm(_, _, changeCount, changesUnknown, _, _) = self else { return false }
        return changesUnknown || (changeCount ?? 0) > 0
    }

    /// Remove anyway rather than Remove.
    var warnsAboutChanges: Bool {
        guard case let .confirm(_, _, _, _, _, changesUnchecked) = self else { return false }
        return losesChanges || changesUnchecked
    }
}

enum WorktreesSnackbar: Equatable {
    case removed(name: String, id: UInt64 = .random(in: 1 ... .max))
    /// `message` is the server's own words.
    /// `message` is the server's own words; nil when the server could not be reached.
    case failed(name: String, directory: String, message: String?, id: UInt64 = .random(in: 1 ... .max))
}

/// Worktrees screen: every worktree of Home's projects, its sessions and uncommitted changes, and
/// removing one. Projects and sessions come from Home through `HomeWorktreesFeed`; Show sessions,
/// New session and a finished remove go back to Home the same way.
@MainActor
@Observable
final class WorktreesViewModel {
    private(set) var groups: [WorktreesGroupItem] = []
    private(set) var actions: WorktreeActionsItem?
    private(set) var dialog: WorktreeRemoveDialog?
    private(set) var snackbar: WorktreesSnackbar?
    /// One-shot: back to Home, which already has the request.
    private(set) var closeToken = 0
    /// One-shot: the running session that blocks a remove.
    private(set) var sessionToOpen: SessionListItem?
    /// Home shows worktrees; false hides Show sessions on Home.
    var homeShowsWorktrees = true

    var worktreeCount: Int { groups.reduce(0) { $0 + $1.worktrees.count } }
    var projectCount: Int { groups.count }

    @ObservationIgnored private let feed: HomeWorktreesFeed
    @ObservationIgnored private let repository: any WorktreeRepository
    @ObservationIgnored private let changeCount: @Sendable (String) async -> Int?
    @ObservationIgnored private var changeCounts: [String: Int?] = [:]
    @ObservationIgnored private var changesRequested: Set<String> = []
    @ObservationIgnored private var removing: Set<String> = []
    /// Removed here; hidden until Home's list no longer has them.
    @ObservationIgnored private var removed: Set<String> = []
    @ObservationIgnored private var snapshot = HomeWorktreesSnapshot()

    init(
        feed: HomeWorktreesFeed,
        repository: any WorktreeRepository,
        changeCount: @escaping @Sendable (String) async -> Int?
    ) {
        self.feed = feed
        self.repository = repository
        self.changeCount = changeCount
    }

    /// Reads Home's latest projects and sessions and loads change counts not asked for yet. The
    /// view calls it on appear and whenever the feed changes.
    func sync() {
        if feed.snapshot.projects.isEmpty {
            // Another server or a disconnect: its counts do not apply.
            changeCounts.removeAll()
            changesRequested.removeAll()
        }
        snapshot = feed.snapshot
        loadChangeCounts()
        publish()
    }

    func openActions(directory: String) {
        guard let (project, worktree) = find(directory) else { return }
        actions = WorktreeActionsItem(
            projectKey: project.key,
            directory: worktree.directory,
            name: worktree.folderName,
            projectName: project.displayName,
            sessionCount: snapshot.sessions(in: worktree.directory).count,
            canShowOnHome: homeShowsWorktrees
        )
    }

    func dismissActions() {
        actions = nil
    }

    func showOnHome(directory: String) {
        guard let (project, worktree) = find(directory) else { return }
        actions = nil
        feed.send(.showSessions(projectKey: project.key, directory: worktree.directory))
        closeToken += 1
    }

    func newSessionHere(directory: String) {
        guard let (_, worktree) = find(directory) else { return }
        actions = nil
        feed.send(.newSession(directory: worktree.directory))
        closeToken += 1
    }

    /// Remove worktree…: a running session blocks it; otherwise confirm.
    func requestRemove(directory: String) {
        guard let (_, worktree) = find(directory) else { return }
        actions = nil
        let sessions = snapshot.sessions(in: worktree.directory)
        if let running = sessions.first(where: \.isRunning) {
            dialog = .blocked(
                name: worktree.folderName,
                session: SessionListItem(id: running.id, title: running.title, worktree: running.directory)
            )
            return
        }
        dialog = .confirm(
            directory: worktree.directory,
            name: worktree.folderName,
            changeCount: changeCounts[worktree.directory] ?? nil,
            changesUnknown: false,
            sessionCount: sessions.count,
            // Still loading or failed. v1 removes with force, so say it rather than act clean.
            changesUnchecked: (changeCounts[worktree.directory] ?? nil) == nil
        )
    }

    func dismissDialog() {
        dialog = nil
    }

    func confirmRemove() async {
        guard let dialog, case let .confirm(directory, _, _, _, _, _) = dialog else { return }
        self.dialog = nil
        await remove(directory: directory, force: dialog.losesChanges)
    }

    func showRunningSession() {
        guard case let .blocked(_, session)? = dialog else { return }
        dialog = nil
        sessionToOpen = session
    }

    func consumeSessionToOpen() {
        sessionToOpen = nil
    }

    /// Retry from the failure snackbar: the same remove, after the same confirmation.
    func retry(directory: String) {
        snackbar = nil
        requestRemove(directory: directory)
    }

    func snackbarShown() {
        snackbar = nil
    }

    private func remove(directory: String, force: Bool) async {
        guard let (project, worktree) = find(directory), !removing.contains(worktree.directory) else { return }
        removing.insert(worktree.directory)
        publish()
        let request = RemoveWorktreeRequest(
            projectID: project.projectID,
            projectDirectory: project.mainCheckoutDirectory,
            directory: worktree.directory,
            force: force
        )
        var failure: WorktreeRemoveError?
        do {
            try await repository.remove(request)
        } catch let error as WorktreeRemoveError {
            failure = error
        } catch {
            failure = .failed(message: error.localizedDescription)
        }
        removing.remove(worktree.directory)
        switch failure {
        case nil:
            removed.insert(worktree.directory)
            feed.send(.removed(projectKey: project.key, directory: worktree.directory))
            snackbar = .removed(name: worktree.folderName)
        case .forceRequired?:
            // The count was unknown or stale: say changes will be lost, then force.
            let count = changeCounts[worktree.directory] ?? nil
            changeCounts[worktree.directory] = .some(nil)
            dialog = .confirm(
                directory: worktree.directory,
                name: worktree.folderName,
                changeCount: count.flatMap { $0 > 0 ? $0 : nil },
                changesUnknown: true,
                sessionCount: snapshot.sessions(in: worktree.directory).count
            )
        case let error?:
            DiagnosticLog.warning(.session, "worktree remove failed: \(error.message)")
            let message: String? = if case .unreachable = error { nil } else { error.message }
            snackbar = .failed(name: worktree.folderName, directory: worktree.directory, message: message)
        }
        publish()
    }

    private func loadChangeCounts() {
        let directories = snapshot.projects
            .flatMap { snapshot.removableWorktrees($0) }
            .map(\.directory)
            .filter { changesRequested.insert($0).inserted }
        for directory in directories {
            Task { [weak self, changeCount] in
                let count = await changeCount(directory)
                guard let self else { return }
                self.changeCounts[directory] = count
                self.publish()
            }
        }
    }

    private func find(_ directory: String) -> (HomeProject, HomeWorktree)? {
        let key = worktreePathKey(directory)
        for project in snapshot.projects {
            if let worktree = snapshot.removableWorktrees(project).first(where: { worktreePathKey($0.directory) == key }) {
                return (project, worktree)
            }
        }
        return nil
    }

    private func publish() {
        // Home lists the project again after a remove; until then the row stays hidden here.
        removed = removed.filter { find($0) != nil }
        let next = snapshot.projects.filter(\.isRepository).map { project in
            WorktreesGroupItem(
                projectKey: project.key,
                displayName: project.displayName,
                primaryPath: project.primaryPath,
                mainCheckout: project.mainCheckoutDirectory,
                worktrees: snapshot.removableWorktrees(project)
                    .filter { !removed.contains($0.directory) }
                    .map { worktree in
                        let sessions = snapshot.sessions(in: worktree.directory)
                        return WorktreeRowItem(
                            projectKey: project.key,
                            directory: worktree.directory,
                            name: worktree.folderName,
                            sessionCount: sessions.count,
                            isRunning: sessions.contains(where: \.isRunning),
                            changeCount: changeCounts[worktree.directory] ?? nil,
                            isRemoving: removing.contains(worktree.directory)
                        )
                    }
            )
        }
        if next != groups { groups = next }
    }
}
