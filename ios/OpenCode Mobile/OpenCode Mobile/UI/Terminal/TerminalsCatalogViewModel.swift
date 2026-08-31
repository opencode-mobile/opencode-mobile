import Foundation
import Observation

struct TerminalCatalogRow: Equatable {
    let id: String
    let title: String
    let projectTitle: String
    let worktree: String

    var worktreeAndId: String { "\(worktree):\(id)" }
}

@MainActor
@Observable
final class TerminalsCatalogViewModel {
    private(set) var rows: [TerminalCatalogRow] = []
    private(set) var isLoading = true
    private(set) var pendingClose: TerminalCatalogRow?

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
            isLoading = false
            return
        }
        let worktrees = await connectionProfileStore.addedWorktrees(serverBaseURL: connection.baseURL.absoluteString)
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
