import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionFailureTests {
    @Test func actionsFollowTheErrorType() {
        #expect(failure("provider.auth").action() == .reconnectProvider)
        #expect(failure("provider.auth").actionLabel() == "Reconnect provider")
        #expect(failure("provider.no-route").action() == .pickModel)
        #expect(failure("unknown").action() == .copy)
        #expect(failure("provider.unknown").action() == .copy)
        #expect(failure("provider.content-filter").action() == nil)
        #expect(failure("provider.rate-limit").action() == nil)
        #expect(failure("permission.rejected").action() == nil)
        #expect(failure("compaction.failed").action() == nil)
    }

    @Test func contextOverflowOffersCompact() {
        #expect(failure("ContextOverflowError", message: "Context overflow").action() == .compact)
        #expect(
            failure("provider.invalid-request", message: "the context is too long", body: "max length").action()
                == .compact
        )
        #expect(failure("provider.invalid-request", message: "bad field").action() == nil)
    }

    @Test func contentFilterHidesTheRetryCard() {
        let hidden = SessionActivity.retrying(
            attempt: 1,
            nextAtMs: 5_000,
            message: "blocked",
            errorType: "provider.content-filter",
            actionLabel: nil,
            actionLink: nil
        ).retryCard
        #expect(hidden == nil)

        let shown = SessionActivity.retrying(
            attempt: 2,
            nextAtMs: 5_000,
            message: "  Slow down please, this reason is longer than eighty characters and must be cut off here now ",
            errorType: "provider.rate-limit",
            actionLabel: " Upgrade ",
            actionLink: " https://example.com "
        ).retryCard
        #expect(shown?.attempt == 2)
        #expect(shown?.nextAtMs == 5_000)
        #expect(shown?.message.count == 80)
        #expect(shown?.actionLabel == "Upgrade")
        #expect(shown?.actionLink == "https://example.com")
    }

    private func failure(_ type: String, message: String = "failed", body: String? = nil) -> SessionFailure {
        SessionFailure(type: type, message: message, status: nil, providerBody: body)
    }
}
