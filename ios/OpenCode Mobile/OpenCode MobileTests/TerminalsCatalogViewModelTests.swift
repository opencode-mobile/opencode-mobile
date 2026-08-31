import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct TerminalsCatalogViewModelTests {
    @Test func startsLoading() {
        let viewModel = makeViewModel(
            repository: FakeCatalogTerminalRepository(running: []),
            worktrees: ["/repo"]
        )

        #expect(viewModel.isLoading)
        #expect(viewModel.rows.isEmpty)
    }

    @Test func appear_loadsRunningRows() async {
        let repository = FakeCatalogTerminalRepository(
            running: [
                RunningTerminal(
                    directory: "/Users/ivan/Projects/opencode-mobile",
                    session: session(id: "pty_1", title: "zsh")
                ),
                RunningTerminal(
                    directory: "/Users/ivan/Projects/playground",
                    session: session(id: "pty_2", title: "term")
                ),
            ]
        )
        let viewModel = makeViewModel(
            repository: repository,
            worktrees: ["/Users/ivan/Projects/opencode-mobile", "/Users/ivan/Projects/playground"]
        )

        await viewModel.appear()

        #expect(
            viewModel.rows == [
                TerminalCatalogRow(
                    id: "pty_1",
                    title: "zsh",
                    projectTitle: "opencode-mobile",
                    worktree: "/Users/ivan/Projects/opencode-mobile"
                ),
                TerminalCatalogRow(
                    id: "pty_2",
                    title: "term",
                    projectTitle: "playground",
                    worktree: "/Users/ivan/Projects/playground"
                ),
            ]
        )
        #expect(repository.listedDirectories == [["/Users/ivan/Projects/opencode-mobile", "/Users/ivan/Projects/playground"]])
        #expect(!viewModel.isLoading)
    }

    @Test func appear_emptyList() async {
        let viewModel = makeViewModel(
            repository: FakeCatalogTerminalRepository(running: []),
            worktrees: ["/repo"]
        )

        await viewModel.appear()

        #expect(viewModel.rows.isEmpty)
        #expect(!viewModel.isLoading)
    }

    @Test func catalogTitle_emptyCommandFallsBackToZsh() {
        let session = TerminalSession(
            id: "pty_1",
            title: "  ",
            command: "",
            cwd: "/repo",
            status: .running
        )
        #expect(catalogTitle(session) == "zsh")
    }

    @Test func catalogTitle_usesCommandBasename() {
        let session = TerminalSession(
            id: "pty_1",
            title: "",
            command: "/bin/bash",
            cwd: "/repo",
            status: .running
        )
        #expect(catalogTitle(session) == "bash")
    }

    @Test func requestClose_setsPendingWithoutRemoving() async {
        let repository = FakeCatalogTerminalRepository(
            running: [RunningTerminal(directory: "/repo", session: session(id: "pty_1", title: "zsh"))]
        )
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo"])

        await viewModel.appear()
        let row = viewModel.rows[0]
        viewModel.requestClose(row)

        #expect(viewModel.pendingClose == row)
        #expect(repository.removed.isEmpty)
    }

    @Test func dismissClose_clearsPendingWithoutRemoving() async {
        let repository = FakeCatalogTerminalRepository(
            running: [RunningTerminal(directory: "/repo", session: session(id: "pty_1", title: "zsh"))]
        )
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo"])

        await viewModel.appear()
        viewModel.requestClose(viewModel.rows[0])
        viewModel.dismissClose()

        #expect(viewModel.pendingClose == nil)
        #expect(repository.removed.isEmpty)
        #expect(viewModel.rows.map(\.id) == ["pty_1"])
    }

    @Test func confirmClose_removesViaRepositoryAndUpdatesState() async {
        let repository = FakeCatalogTerminalRepository(
            running: [
                RunningTerminal(directory: "/repo", session: session(id: "pty_1", title: "zsh")),
                RunningTerminal(directory: "/play", session: session(id: "pty_2", title: "zsh")),
            ]
        )
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo", "/play"])

        await viewModel.appear()
        viewModel.requestClose(viewModel.rows[0])
        await viewModel.confirmClose()

        #expect(viewModel.pendingClose == nil)
        #expect(repository.removed.map { $0.0 } == ["pty_1"])
        #expect(repository.removed.map { $0.1 } == ["/repo"])
        #expect(viewModel.rows.map(\.id) == ["pty_2"])
    }

    @Test func confirmClose_withoutPending_isNoOp() async {
        let repository = FakeCatalogTerminalRepository(
            running: [RunningTerminal(directory: "/repo", session: session(id: "pty_1", title: "zsh"))]
        )
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo"])

        await viewModel.appear()
        await viewModel.confirmClose()

        #expect(repository.removed.isEmpty)
        #expect(viewModel.rows.map(\.id) == ["pty_1"])
    }

    private func makeViewModel(
        repository: FakeCatalogTerminalRepository,
        worktrees: [String]
    ) -> TerminalsCatalogViewModel {
        let store = FakeCatalogProfileStore(worktrees: worktrees)
        let manager = ActiveConnectionManager()
        manager.applyConnection(
            ServerConnection(
                baseURL: URL(string: "http://127.0.0.1:4096/")!,
                username: "opencode",
                password: "secret",
                serverProtocol: .v1
            )
        )
        return TerminalsCatalogViewModel(
            repository: repository,
            activeConnectionManager: manager,
            connectionProfileStore: store
        )
    }

    private func session(id: String, title: String) -> TerminalSession {
        TerminalSession(
            id: id,
            title: title,
            command: "/bin/zsh",
            cwd: "/repo",
            status: .running
        )
    }
}

private final class FakeCatalogTerminalRepository: TerminalRepository, @unchecked Sendable {
    private let running: [RunningTerminal]
    var listedDirectories: [[String]] = []
    var removed: [(String, String)] = []

    init(running: [RunningTerminal]) {
        self.running = running
    }

    func list(directory: String) async throws -> [TerminalSession] {
        throw TerminalError.transport("unused")
    }

    func create(directory: String, cwd: String) async throws -> TerminalSession {
        throw TerminalError.transport("unused")
    }

    func get(id: String, directory: String) async throws -> TerminalSession {
        throw TerminalError.transport("unused")
    }

    func updateSize(id: String, directory: String, cols: Int, rows: Int) async throws -> TerminalSession {
        throw TerminalError.transport("unused")
    }

    func ensureRunning(directory: String) async throws -> TerminalSession {
        throw TerminalError.transport("unused")
    }

    func remove(id: String, directory: String) async throws {
        removed.append((id, directory))
    }

    func listRunning(directories: [String]) async throws -> [RunningTerminal] {
        listedDirectories.append(directories)
        return running.filter { directories.contains($0.directory) }
    }

    func connectRequest(
        id: String,
        directory: String,
        serverProtocol: ServerProtocol,
        baseURL: String,
        cursor: String,
        ticket: String?,
        username: String,
        password: String
    ) -> TerminalConnectRequest {
        TerminalConnectRequest(url: "ws://example.test/pty/\(id)/connect")
    }
}

private final class FakeCatalogProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private let worktrees: [String]

    init(worktrees: [String]) {
        self.worktrees = worktrees
    }

    func activeProfile() async -> ConnectionProfile? { nil }

    func saveProfile(_ profile: ConnectionProfile) async throws {}

    func addedWorktrees(serverBaseURL: String) async -> [String] { worktrees }

    func addWorktree(serverBaseURL: String, worktree: String) async {}

    func removeWorktree(serverBaseURL: String, worktree: String) async {}

    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {}

    func clearAll() async {}
}
