import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionContextViewModelTests {
    @Test func refreshCancellationKeepsLoadedSnapshot() async {
        let repository = FakeSessionContextRepository(results: [
            .success(Self.snapshot(title: "Loaded")),
            .failure(CancellationError()),
        ])
        let viewModel = SessionContextViewModel(sessionID: "ses-1", worktree: "/repo", repository: repository)

        await viewModel.load()
        await viewModel.refresh()

        guard case let .loaded(display) = viewModel.state else {
            Issue.record("Expected loaded state after cancellation")
            return
        }
        #expect(display.stats.first { $0.id == "session" }?.value == "Loaded")
        #expect(viewModel.refreshErrorMessage == nil)
    }

    @Test func refreshFailureKeepsLoadedSnapshotAndShowsBanner() async {
        let repository = FakeSessionContextRepository(results: [
            .success(Self.snapshot(title: "Loaded")),
            .failure(SessionContextError.underlying("Refresh failed")),
        ])
        let viewModel = SessionContextViewModel(sessionID: "ses-1", worktree: "/repo", repository: repository)

        await viewModel.load()
        await viewModel.refresh()

        guard case let .loaded(display) = viewModel.state else {
            Issue.record("Expected loaded state after refresh failure")
            return
        }
        #expect(display.stats.first { $0.id == "session" }?.value == "Loaded")
        #expect(viewModel.refreshErrorMessage == "Refresh failed")
    }

    private static func snapshot(title: String) -> SessionContextSnapshot {
        SessionContextSnapshot(
            sessionID: "ses-1",
            sessionTitle: title,
            providerLabel: "Provider",
            modelLabel: "Model",
            tokenStats: nil,
            messageCounts: SessionContextMessageCounts(total: 0, user: 0, assistant: 0),
            totalCost: 0,
            createdAtEpochMs: nil,
            lastActivityEpochMs: nil,
            breakdown: [],
            rawMessages: []
        )
    }
}

private final class FakeSessionContextRepository: SessionContextRepository, @unchecked Sendable {
    private var results: [Result<SessionContextSnapshot, Error>]

    init(results: [Result<SessionContextSnapshot, Error>]) {
        self.results = results
    }

    func loadSessionContext(sessionID: String, directory: String?) async throws -> SessionContextSnapshot {
        let result = results.removeFirst()
        return try result.get()
    }
}
