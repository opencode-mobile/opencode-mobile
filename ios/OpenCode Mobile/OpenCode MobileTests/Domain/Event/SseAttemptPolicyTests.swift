import Testing
@testable import OpenCode_Mobile

struct SseAttemptPolicyTests {
    @Test func http401And403AreAuthFailedWithSlowRetry() {
        for status in [401, 403] {
            let decision = SseAttemptPolicy.decide(
                outcome: .http(status),
                protocolConfirmed: false,
                attempt: 2,
                delayMs: { _ in 9_999 },
                authDelayMs: { $0 * 10_000 }
            )
            #expect(decision == .authFailed(retryAfterMs: 20_000))
        }
    }

    @Test func http404FastRetriesOnlyWhileProtocolUnconfirmed() {
        let unconfirmed = SseAttemptPolicy.decide(
            outcome: .http(404),
            protocolConfirmed: false,
            attempt: 4,
            delayMs: { _ in 8_000 }
        )
        #expect(unconfirmed == .retry(afterMs: 1_000))

        let confirmed = SseAttemptPolicy.decide(
            outcome: .http(404),
            protocolConfirmed: true,
            attempt: 4,
            delayMs: { $0 * 1_000 }
        )
        #expect(confirmed == .retry(afterMs: 4_000))
    }

    @Test func otherErrorsUseBackoff() {
        let ended = SseAttemptPolicy.decide(
            outcome: .ended,
            protocolConfirmed: true,
            attempt: 2,
            delayMs: { $0 * 1_000 }
        )
        #expect(ended == .retry(afterMs: 2_000))

        let transport = SseAttemptPolicy.decide(
            outcome: .transport(nil),
            protocolConfirmed: true,
            attempt: 3,
            delayMs: { $0 * 1_000 }
        )
        #expect(transport == .retry(afterMs: 3_000))

        let serverError = SseAttemptPolicy.decide(
            outcome: .http(500),
            protocolConfirmed: true,
            attempt: 1,
            delayMs: { $0 * 1_000 }
        )
        #expect(serverError == .retry(afterMs: 1_000))
    }
}
