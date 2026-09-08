import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct TerminalViewModelTests {
    @Test func appearSuccess_showsLiveWithSessionId() async {
        let viewModel = makeViewModel()

        await viewModel.appear()

        guard case let .live(session) = viewModel.state else {
            Issue.record("expected live")
            return
        }
        #expect(session.id == "pty_1")
    }

    @Test func appearWithPtyId_connectsThatIdWithoutEnsureRunning() async {
        let repository = FakeTerminalRepository(
            results: [],
            getResults: [.success(runningSession(id: "pty_specific"))]
        )
        let viewModel = makeViewModel(repository: repository, ptyId: "pty_specific")

        await viewModel.appear()

        guard case let .live(session) = viewModel.state else {
            Issue.record("expected live")
            return
        }
        #expect(session.id == "pty_specific")
        #expect(repository.ensureRunningCalls == 0)
        #expect(repository.getCalls.map(\.0) == ["pty_specific"])
        #expect(repository.getCalls.map(\.1) == ["/repo"])
    }

    @Test func appearWithoutPtyId_stillEnsureRunning() async {
        let repository = FakeTerminalRepository(results: [.success(runningSession())])
        let viewModel = makeViewModel(repository: repository)

        await viewModel.appear()

        #expect(repository.ensureRunningCalls == 1)
        #expect(repository.getCalls.isEmpty)
    }

    @Test func appearThrow_showsConnectFailed() async {
        let viewModel = makeViewModel(
            repository: FakeTerminalRepository(results: [
                .failure(TerminalError.transport("nope")),
            ])
        )

        await viewModel.appear()

        #expect(viewModel.state == .connectFailed)
    }

    @Test func retryAfterFail_showsLive() async {
        let viewModel = makeViewModel(
            repository: FakeTerminalRepository(results: [
                .failure(TerminalError.transport("nope")),
                .success(runningSession()),
            ])
        )

        await viewModel.appear()
        await viewModel.retry()

        guard case let .live(session) = viewModel.state else {
            Issue.record("expected live")
            return
        }
        #expect(session.id == "pty_1")
    }

    @Test func disappear_closesSocket() async {
        let socket = FakeTerminalSocketClient()
        let viewModel = makeViewModel(socket: socket)

        await viewModel.appear()
        viewModel.disappear()

        #expect(socket.closed)
    }

    @Test func abnormalClose_getRunning_showsReconnecting() async {
        let socket = FakeTerminalSocketClient()
        let viewModel = makeViewModel(
            repository: FakeTerminalRepository(
                results: [.success(runningSession())],
                getResults: [.success(runningSession())]
            ),
            socket: socket
        )

        await viewModel.appear()
        socket.autoOpen = false
        socket.abnormalClose()
        await waitUntil {
            if case .reconnecting = viewModel.state { return true }
            return false
        }

        guard case let .reconnecting(session) = viewModel.state else {
            Issue.record("expected reconnecting")
            return
        }
        #expect(session.id == "pty_1")
    }

    @Test func abnormalClose_getExited_showsExited() async {
        let socket = FakeTerminalSocketClient()
        let viewModel = makeViewModel(
            repository: FakeTerminalRepository(
                results: [.success(runningSession())],
                getResults: [.success(runningSession(status: .exited))]
            ),
            socket: socket
        )

        await viewModel.appear()
        socket.abnormalClose()
        await waitUntil { viewModel.state == .exited }

        #expect(viewModel.state == .exited)
    }

    private func makeViewModel(
        repository: FakeTerminalRepository? = nil,
        socket: FakeTerminalSocketClient? = nil,
        ptyId: String? = nil
    ) -> TerminalViewModel {
        let manager = ActiveConnectionManager()
        manager.applyConnection(
            ServerConnection(
                baseURL: URL(string: "http://127.0.0.1:4096/")!,
                username: "opencode",
                password: "secret",
                serverProtocol: .v1
            )
        )
        return TerminalViewModel(
            worktree: "/repo",
            ptyId: ptyId,
            repository: repository ?? FakeTerminalRepository(results: [.success(runningSession())]),
            activeConnectionManager: manager,
            socketClient: socket ?? FakeTerminalSocketClient()
        )
    }

    private func waitUntil(_ condition: () -> Bool) async {
        for _ in 0..<50 {
            if condition() { return }
            await Task.yield()
        }
    }
}

private func runningSession(id: String = "pty_1", status: TerminalSessionStatus = .running) -> TerminalSession {
    TerminalSession(
        id: id,
        title: "term",
        command: "/bin/zsh",
        cwd: "/repo",
        status: status
    )
}

@MainActor
private final class FakeTerminalSocketClient: TerminalSocketClient {
    var autoOpen = true
    var closed = false
    private var listener: TerminalSocketListener?

    func open(url: String, listener: TerminalSocketListener, authorization: String?) {
        closed = false
        self.listener = listener
        if autoOpen {
            listener.didOpen()
        }
    }

    func sendText(_ text: String) {}

    func close() {
        closed = true
    }

    func abnormalClose(code: Int = 1006) {
        listener?.didClose(code: code)
    }
}

private final class FakeTerminalRepository: TerminalRepository, @unchecked Sendable {
    private var results: [Result<TerminalSession, Error>]
    private var getResults: [Result<TerminalSession, Error>]
    var ensureRunningCalls = 0
    var getCalls: [(String, String)] = []

    init(
        results: [Result<TerminalSession, Error>],
        getResults: [Result<TerminalSession, Error>] = []
    ) {
        self.results = results
        self.getResults = getResults
    }

    func list(directory: String) async throws -> [TerminalSession] {
        throw TerminalError.transport("unused")
    }

    func create(directory: String, cwd: String) async throws -> TerminalSession {
        throw TerminalError.transport("unused")
    }

    func get(id: String, directory: String) async throws -> TerminalSession {
        getCalls.append((id, directory))
        return try getResults.removeFirst().get()
    }

    func updateSize(id: String, directory: String, cols: Int, rows: Int) async throws -> TerminalSession {
        throw TerminalError.transport("unused")
    }

    func ensureRunning(directory: String) async throws -> TerminalSession {
        ensureRunningCalls += 1
        return try results.removeFirst().get()
    }

    func remove(id: String, directory: String) async throws {
        throw TerminalError.transport("unused")
    }

    func listRunning(directories: [String]) async throws -> [RunningTerminal] {
        throw TerminalError.transport("unused")
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
