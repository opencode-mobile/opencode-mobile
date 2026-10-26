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
                    directory: "/Users/example/Projects/opencode-mobile",
                    session: session(id: "pty_1", title: "zsh")
                ),
                RunningTerminal(
                    directory: "/Users/example/Projects/playground",
                    session: session(id: "pty_2", title: "term")
                ),
            ]
        )
        let viewModel = makeViewModel(
            repository: repository,
            worktrees: ["/Users/example/Projects/opencode-mobile", "/Users/example/Projects/playground"]
        )

        await viewModel.appear()

        #expect(
            viewModel.rows == [
                TerminalCatalogRow(
                    id: "pty_1",
                    title: "zsh",
                    projectTitle: "opencode-mobile",
                    worktree: "/Users/example/Projects/opencode-mobile"
                ),
                TerminalCatalogRow(
                    id: "pty_2",
                    title: "term",
                    projectTitle: "playground",
                    worktree: "/Users/example/Projects/playground"
                ),
            ]
        )
        #expect(repository.listedDirectories == [["/Users/example/Projects/opencode-mobile", "/Users/example/Projects/playground"]])
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

    @Test func appear_exposesProjectsFromAddedWorktrees() async {
        let viewModel = makeViewModel(
            repository: FakeCatalogTerminalRepository(running: []),
            worktrees: ["/Users/example/Projects/opencode-mobile", "/Users/example/Projects/playground"]
        )

        await viewModel.appear()

        #expect(
            viewModel.projects == [
                TerminalCatalogProject(worktree: "/Users/example/Projects/opencode-mobile", title: "opencode-mobile"),
                TerminalCatalogProject(worktree: "/Users/example/Projects/playground", title: "playground"),
            ]
        )
    }

    @Test func showProjectPicker_setsVisible_dismissClearsVisibleAndCreateError() async {
        let repository = FakeCatalogTerminalRepository(
            running: [],
            createError: TerminalError.transport("nope")
        )
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo"])

        await viewModel.appear()
        viewModel.showProjectPicker()
        #expect(viewModel.isProjectPickerVisible)

        _ = await viewModel.createShell(worktree: "/repo")
        #expect(viewModel.createError == "Could not start a shell.")

        viewModel.dismissProjectPicker()

        #expect(!viewModel.isProjectPickerVisible)
        #expect(viewModel.createError == nil)
    }

    @Test func createShell_createsWithDirectoryAndCwd_prependsRow() async {
        let repository = FakeCatalogTerminalRepository(
            running: [RunningTerminal(directory: "/repo", session: session(id: "pty_1", title: "zsh"))]
        )
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo", "/play"])

        await viewModel.appear()
        viewModel.showProjectPicker()
        let row = await viewModel.createShell(worktree: "/play")

        #expect(
            row == TerminalCatalogRow(
                id: "pty_created",
                title: "zsh",
                projectTitle: "play",
                worktree: "/play"
            )
        )
        #expect(viewModel.rows.first == row)
        #expect(viewModel.rows.map(\.id) == ["pty_created", "pty_1"])
        #expect(repository.created.map(\.directory) == ["/play"])
        #expect(repository.created.map(\.cwd) == ["/play"])
        #expect(repository.ensureRunningCalls == 0)
        #expect(!viewModel.isProjectPickerVisible)
        #expect(viewModel.createError == nil)
    }

    @Test func createShell_failure_leavesRowsUnchanged() async {
        let repository = FakeCatalogTerminalRepository(
            running: [RunningTerminal(directory: "/repo", session: session(id: "pty_1", title: "zsh"))],
            createError: TerminalError.transport("nope")
        )
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo"])

        await viewModel.appear()
        viewModel.showProjectPicker()
        let result = await viewModel.createShell(worktree: "/repo")

        #expect(result == nil)
        #expect(viewModel.rows.map(\.id) == ["pty_1"])
        #expect(viewModel.isProjectPickerVisible)
        #expect(viewModel.createError == "Could not start a shell.")
        #expect(repository.created.map(\.directory) == ["/repo"])
        #expect(repository.ensureRunningCalls == 0)
    }

    @Test func createShell_whileCreating_doesNotCallCreateAgain() async {
        let repository = FakeCatalogTerminalRepository(running: [], hangCreate: true)
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo"])

        await viewModel.appear()
        let first = Task { await viewModel.createShell(worktree: "/repo") }
        await waitUntil { repository.created.count == 1 }
        defer { repository.resumeHungCreate() }

        let second = await viewModel.createShell(worktree: "/repo")

        #expect(second == nil)
        #expect(repository.created.count == 1)

        repository.resumeHungCreate()
        let row = await first.value
        #expect(row?.id == "pty_created")
    }

    @Test func createShell_unknownWorktree_doesNotCreate() async {
        let repository = FakeCatalogTerminalRepository(running: [])
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo"])

        await viewModel.appear()
        viewModel.showProjectPicker()
        let result = await viewModel.createShell(worktree: "/unknown")

        #expect(result == nil)
        #expect(repository.created.isEmpty)
        #expect(viewModel.isProjectPickerVisible)
        #expect(viewModel.rows.isEmpty)
    }

    @Test func createShell_refreshReturnsNewPtyWhileCreateInFlight_keepsSingleRow() async {
        let repository = FakeCatalogTerminalRepository(
            running: [RunningTerminal(directory: "/repo", session: session(id: "pty_1", title: "term"))],
            hangCreate: true
        )
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo"])

        await viewModel.appear()
        let create = Task { await viewModel.createShell(worktree: "/repo") }
        await waitUntil { repository.created.count == 1 }
        repository.running = [
            RunningTerminal(directory: "/repo", session: session(id: "pty_1", title: "term")),
            RunningTerminal(directory: "/repo", session: session(id: "pty_created", title: "zsh")),
        ]
        await viewModel.appear()
        repository.resumeHungCreate()
        _ = await create.value

        let keys = viewModel.rows.map(\.worktreeAndId)
        #expect(keys.filter { $0 == "/repo:pty_created" }.count == 1)
        #expect(Set(keys).count == keys.count)
        #expect(Set(viewModel.rows.map(\.id)) == ["pty_1", "pty_created"])
    }

    @Test func createShell_staleRefreshLandingAfterCreate_keepsCreatedRow() async {
        let repository = FakeCatalogTerminalRepository(
            running: [RunningTerminal(directory: "/repo", session: session(id: "pty_1", title: "term"))]
        )
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo"])

        await viewModel.appear()
        repository.hangList = true
        let refresh = Task { await viewModel.appear() }
        await waitUntil { repository.listedDirectories.count == 2 }
        _ = await viewModel.createShell(worktree: "/repo")
        repository.resumeHungList()
        await refresh.value

        #expect(viewModel.rows.map(\.id) == ["pty_created", "pty_1"])
    }

    @Test func appear_startedAfterCreate_isAuthoritative() async {
        let repository = FakeCatalogTerminalRepository(
            running: [RunningTerminal(directory: "/repo", session: session(id: "pty_1", title: "term"))]
        )
        let viewModel = makeViewModel(repository: repository, worktrees: ["/repo"])

        await viewModel.appear()
        _ = await viewModel.createShell(worktree: "/repo")
        await viewModel.appear()

        #expect(viewModel.rows.map(\.id) == ["pty_1"])
    }

    private func waitUntil(_ condition: @MainActor () -> Bool) async {
        for _ in 0 ..< 50 {
            if condition() { return }
            await Task.yield()
        }
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
    var running: [RunningTerminal]
    private let createError: Error?
    private let hangCreate: Bool
    private let hangLock = NSLock()
    private var hangContinuation: CheckedContinuation<Void, Never>?
    var hangList = false
    private var listContinuation: CheckedContinuation<Void, Never>?
    var listedDirectories: [[String]] = []
    var removed: [(String, String)] = []
    var created: [(directory: String, cwd: String)] = []
    var ensureRunningCalls = 0

    init(
        running: [RunningTerminal],
        createError: Error? = nil,
        hangCreate: Bool = false
    ) {
        self.running = running
        self.createError = createError
        self.hangCreate = hangCreate
    }

    func list(directory: String) async throws -> [TerminalSession] {
        throw TerminalError.transport("unused")
    }

    func create(directory: String, cwd: String) async throws -> TerminalSession {
        created.append((directory, cwd))
        if hangCreate {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                hangLock.lock()
                hangContinuation = continuation
                hangLock.unlock()
            }
        }
        if let createError { throw createError }
        return TerminalSession(
            id: "pty_created",
            title: "zsh",
            command: "/bin/zsh",
            cwd: directory,
            status: .running
        )
    }

    func resumeHungCreate() {
        hangLock.lock()
        hangContinuation?.resume()
        hangContinuation = nil
        hangLock.unlock()
    }

    func resumeHungList() {
        hangLock.lock()
        listContinuation?.resume()
        listContinuation = nil
        hangLock.unlock()
    }

    func get(id: String, directory: String) async throws -> TerminalSession {
        throw TerminalError.transport("unused")
    }

    func updateSize(id: String, directory: String, cols: Int, rows: Int) async throws -> TerminalSession {
        throw TerminalError.transport("unused")
    }

    func ensureRunning(directory: String) async throws -> TerminalSession {
        ensureRunningCalls += 1
        throw TerminalError.transport("unused")
    }

    func remove(id: String, directory: String) async throws {
        removed.append((id, directory))
    }

    func listRunning(directories: [String]) async throws -> [RunningTerminal] {
        listedDirectories.append(directories)
        let snapshot = running
        if hangList {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                hangLock.lock()
                listContinuation = continuation
                hangLock.unlock()
            }
        }
        return snapshot.filter { directories.contains($0.directory) }
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
