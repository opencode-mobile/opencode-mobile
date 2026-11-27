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

    @Test func statLabelsAreCatalogKeysAndValuesStayVerbatim() async {
        let repository = FakeSessionContextRepository(results: [.success(Self.snapshot(title: "Loaded"))])
        let viewModel = SessionContextViewModel(sessionID: "ses-1", worktree: "/repo", repository: repository)

        await viewModel.load()

        guard case let .loaded(display) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        let session = display.stats.first { $0.id == "session" }
        let sessionKey: OpenCodeText = "session_context.stat.session"
        #expect(session?.label == sessionKey)
        #expect(session?.label.resolved == "Session")
        #expect(session?.value == "Loaded")
        #expect(display.stats.first { $0.id == "cache" }?.label.resolved == "Cache Tokens (read/write)")
    }

    @Test func numbersCostsAndDatesKeepTheUSFormatsInEnglish() async {
        let stats = await Self.formattedStats(locale: Locale(identifier: "en_US"))

        #expect(stats["limit"] == "1,234,567")
        #expect(stats["cache"] == "12,000 / 340")
        #expect(stats["usage"] == "45%")
        #expect(stats["cost"] == "$1,234.50")
        #expect(stats["created"] == "Nov 27, 2026 at 3:04\u{202F}PM")
    }

    @Test func numbersCostsAndDatesFollowAGermanLocaleButStayInDollars() async {
        let stats = await Self.formattedStats(locale: Locale(identifier: "de_DE"))

        #expect(stats["limit"] == "1.234.567")
        #expect(stats["cache"] == "12.000 / 340")
        #expect(stats["usage"] == "45\u{00A0}%")
        #expect(stats["cost"] == "1.234,50\u{00A0}$")
        #expect(stats["created"] == "27.11.2026, 15:04")
    }

    @Test func breakdownPercentFollowsTheLocale() {
        #expect(SessionContextView.breakdownPercent(12.5, locale: Locale(identifier: "en_US")) == "12.5%")
        #expect(SessionContextView.breakdownPercent(12.5, locale: Locale(identifier: "de_DE")) == "12,5\u{00A0}%")
    }

    /// Stat values by id for a snapshot with numbers, formatted in `locale` and UTC.
    private static func formattedStats(locale: Locale) async -> [String: String] {
        let snapshot = SessionContextSnapshot(
            sessionID: "ses-1",
            sessionTitle: "Loaded",
            providerLabel: "Provider",
            modelLabel: "Model",
            tokenStats: SessionContextTokenStats(
                contextLimit: 1_234_567,
                total: 12_340,
                input: 12_000,
                output: 340,
                reasoning: 0,
                cacheRead: 12_000,
                cacheWrite: 340,
                usagePercent: 45
            ),
            messageCounts: SessionContextMessageCounts(total: 2, user: 1, assistant: 1),
            totalCost: 1_234.5,
            // 2026-11-27T15:04:00Z
            createdAtEpochMs: 1_795_791_840_000,
            lastActivityEpochMs: nil,
            breakdown: []
        )
        let viewModel = SessionContextViewModel(
            sessionID: "ses-1",
            worktree: "/repo",
            repository: FakeSessionContextRepository(results: [.success(snapshot)]),
            locale: locale,
            timeZone: TimeZone(identifier: "UTC")!
        )
        await viewModel.load()
        guard case let .loaded(display) = viewModel.state else {
            Issue.record("Expected loaded state")
            return [:]
        }
        return Dictionary(uniqueKeysWithValues: display.stats.map { ($0.id, $0.value) })
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
            breakdown: []
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
