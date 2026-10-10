import Foundation
import Observation

struct TerminalCatalogRow: Equatable {
    let id: String
    let title: String
    let projectTitle: String
    let worktree: String

    var worktreeAndId: String { "\(worktree):\(id)" }
}

struct TerminalCatalogProject: Equatable {
    let worktree: String
    let title: String
}

@MainActor
@Observable
final class TerminalsCatalogViewModel {
    private(set) var rows: [TerminalCatalogRow] = []
    private(set) var isLoading = true
    private(set) var pendingClose: TerminalCatalogRow?
    private(set) var projects: [TerminalCatalogProject] = []
    private(set) var isProjectPickerVisible = false
    private(set) var isCreating = false
    private(set) var createError: String?

    private let repository: any TerminalRepository
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionProfileStore: any ConnectionProfileStore

    init(
        repository: any TerminalRepository,
        activeConnectionManager: ActiveConnectionManager,
        connectionProfileStore: any ConnectionProfileStore
    ) {
        self.repository = repository
        self.activeConnectionManager = activeConnectionManager
        self.connectionProfileStore = connectionProfileStore
    }

    func appear() async {
        guard let connection = activeConnectionManager.connection else {
            projects = []
            isLoading = false
            return
        }
        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: connection.baseURL.absoluteString)
        projects = worktrees.map { worktree in
            TerminalCatalogProject(
                worktree: worktree,
                title: ProjectDomainAdapter.displayName(from: worktree)
            )
        }
        let running = (try? await repository.listRunning(directories: worktrees)) ?? []
        rows = running.map { item in
            TerminalCatalogRow(
                id: item.session.id,
                title: catalogTitle(item.session),
                projectTitle: ProjectDomainAdapter.displayName(from: item.directory),
                worktree: item.directory
            )
        }
        isLoading = false
    }

    func showProjectPicker() {
        isProjectPickerVisible = true
    }

    func dismissProjectPicker() {
        isProjectPickerVisible = false
        createError = nil
    }

    func createShell(worktree: String) async -> TerminalCatalogRow? {
        if isCreating { return nil }
        guard let project = projects.first(where: { $0.worktree == worktree }) else {
            return nil
        }
        isCreating = true
        defer { isCreating = false }
        do {
            let session = try await repository.create(directory: worktree, cwd: worktree)
            let row = TerminalCatalogRow(
                id: session.id,
                title: catalogTitle(session),
                projectTitle: project.title,
                worktree: worktree
            )
            rows.insert(row, at: 0)
            isProjectPickerVisible = false
            createError = nil
            return row
        } catch {
            createError = "Could not start a shell."
            return nil
        }
    }

    func requestClose(_ row: TerminalCatalogRow) {
        pendingClose = row
    }

    func dismissClose() {
        pendingClose = nil
    }

    func confirmClose() async {
        guard let pending = pendingClose else { return }
        pendingClose = nil
        do {
            try await repository.remove(id: pending.id, directory: pending.worktree)
            rows.removeAll { $0.id == pending.id && $0.worktree == pending.worktree }
        } catch {
            return
        }
    }
}

func catalogTitle(_ session: TerminalSession) -> String {
    let titled = session.title.trimmingCharacters(in: .whitespacesAndNewlines)
    if !titled.isEmpty {
        return titled
    }
    let command = session.command.trimmingCharacters(in: .whitespacesAndNewlines)
    if !command.isEmpty {
        let commandName = (command as NSString).lastPathComponent
        if !commandName.isEmpty {
            return commandName
        }
    }
    return "zsh"
}
