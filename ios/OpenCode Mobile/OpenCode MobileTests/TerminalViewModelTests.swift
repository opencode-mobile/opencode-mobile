import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct TerminalViewModelTests {
    @Test func appearSuccess_showsLive() async {
        let viewModel = TerminalViewModel(
            worktree: "/repo",
            repository: FakeTerminalRepository(results: [
                .success(Self.runningSession()),
            ])
        )

        await viewModel.appear()

        #expect(viewModel.state == .live)
    }

    @Test func appearThrow_showsConnectFailed() async {
        let viewModel = TerminalViewModel(
            worktree: "/repo",
            repository: FakeTerminalRepository(results: [
                .failure(TerminalError.transport("nope")),
            ])
        )

        await viewModel.appear()

        #expect(viewModel.state == .connectFailed)
    }

    @Test func retryAfterFail_showsLive() async {
        let viewModel = TerminalViewModel(
            worktree: "/repo",
            repository: FakeTerminalRepository(results: [
                .failure(TerminalError.transport("nope")),
                .success(Self.runningSession()),
            ])
        )

        await viewModel.appear()
        await viewModel.retry()

        #expect(viewModel.state == .live)
    }

    private static func runningSession() -> TerminalSession {
        TerminalSession(
            id: "pty_1",
            title: "term",
            command: "/bin/zsh",
            cwd: "/repo",
            status: .running
        )
    }
}

private final class FakeTerminalRepository: TerminalRepository, @unchecked Sendable {
    private var results: [Result<TerminalSession, Error>]

    init(results: [Result<TerminalSession, Error>]) {
        self.results = results
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
        try results.removeFirst().get()
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
        TerminalConnectRequest(url: "")
    }
}
