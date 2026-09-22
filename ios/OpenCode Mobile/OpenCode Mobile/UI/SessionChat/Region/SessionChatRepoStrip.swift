import Foundation

/// Session Chat region for the repo strip: current branch plus uncommitted working-tree changes.
///
/// Fetches `GET vcs` and `GET vcs/status` for the bound worktree on start, after each turn
/// completes, and on a `vcs.branch.updated` event. A new session starts expanded when the tree
/// is dirty; an active session starts collapsed. The user toggle wins over both defaults.
@MainActor
final class SessionChatRepoStrip {
    static let branchUpdatedEvent = "vcs.branch.updated"

    var onChange: (() -> Void)?

    private(set) var state = SessionChatRepoStripRegionState()

    private let worktree: String
    private let sessionRepository: any SessionRepository
    private let foregroundEventStream: (any ForegroundEventStream)?
    private var expandWhenDirty: Bool
    private var userToggled = false
    private var lastTurnActive: Bool?
    private var refreshTask: Task<Void, Never>?
    private var eventTask: Task<Void, Never>?
    private var started = false

    /// - Parameter startExpanded: first-load default. A new session shows the file list right
    ///   away; an active session shows only the summary row.
    init(
        worktree: String,
        startExpanded: Bool,
        sessionRepository: any SessionRepository,
        foregroundEventStream: (any ForegroundEventStream)? = nil
    ) {
        self.worktree = worktree
        self.expandWhenDirty = startExpanded
        self.sessionRepository = sessionRepository
        self.foregroundEventStream = foregroundEventStream
    }

    func start() {
        guard !started else { return }
        started = true
        refresh()
        guard let foregroundEventStream else { return }
        eventTask?.cancel()
        eventTask = Task { [weak self] in
            for await event in foregroundEventStream.events() {
                if Task.isCancelled { return }
                guard event.type == Self.branchUpdatedEvent else { continue }
                self?.refresh()
            }
        }
    }

    func stop() {
        refreshTask?.cancel()
        refreshTask = nil
        eventTask?.cancel()
        eventTask = nil
        started = false
    }

    func toggle() {
        guard !state.files.isEmpty else { return }
        userToggled = true
        state.isExpanded.toggle()
        onChange?()
    }

    /// Refetches when a turn goes from active to idle so the strip reflects the agent's edits.
    func updateTurnActive(_ isTurnActive: Bool) {
        let previous = lastTurnActive
        lastTurnActive = isTurnActive
        if previous == true, !isTurnActive { refresh() }
    }

    func refresh() {
        let worktree = worktree
        refreshTask?.cancel()
        refreshTask = Task { [weak self, sessionRepository] in
            let info = try? await sessionRepository.getVcsInfo(directory: worktree)
            guard !Task.isCancelled, let self else { return }
            guard let info, let branch = info.currentBranch else {
                self.apply(SessionChatRepoStripRegionState())
                return
            }
            let files: [VcsFileStatus]
            do {
                files = try await sessionRepository.getVcsStatus(directory: worktree)
            } catch let SessionError.http(statusCode, _) where statusCode == 404 {
                // Servers without the status route still show the branch row.
                files = []
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            self.apply(self.resolved(branch: branch, defaultBranch: info.defaultBranch, files: files))
        }
    }

    private func resolved(branch: String, defaultBranch: String?, files: [VcsFileStatus]) -> SessionChatRepoStripRegionState {
        let previous = state
        let isExpanded: Bool =
            if files.isEmpty {
                false
            } else if userToggled {
                previous.isExpanded
            } else if !previous.isAvailable {
                expandWhenDirty
            } else {
                previous.isExpanded
            }
        return SessionChatRepoStripRegionState(
            isAvailable: true,
            branch: branch,
            defaultBranch: defaultBranch,
            files: files,
            isExpanded: isExpanded
        )
    }

    private func apply(_ next: SessionChatRepoStripRegionState) {
        guard next != state else { return }
        state = next
        onChange?()
    }
}
