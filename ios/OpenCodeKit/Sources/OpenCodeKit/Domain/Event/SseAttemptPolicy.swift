import Foundation

public nonisolated enum SseAttemptPolicy {
    public enum Decision: Equatable, Sendable {
        case retry(afterMs: Int)
        /// The server rejected the credential; show it, but keep retrying slowly in case auth recovers.
        case authFailed(retryAfterMs: Int)
    }

    public static let unconfirmedNotFoundRetryMs = 1_000

    public static func decide(
        outcome: SseAttemptOutcome,
        protocolConfirmed: Bool,
        attempt: Int,
        delayMs: (Int) -> Int = { SseRetryBackoff.delayMs(attempt: $0) },
        authDelayMs: (Int) -> Int = { SseRetryBackoff.authDelayMs(attempt: $0) }
    ) -> Decision {
        switch outcome {
        case .http(401), .http(403):
            .authFailed(retryAfterMs: authDelayMs(max(attempt, 1)))
        case let .http(status) where status == 404 && !protocolConfirmed:
            .retry(afterMs: unconfirmedNotFoundRetryMs)
        case .ended, .http, .transport:
            .retry(afterMs: delayMs(max(attempt, 1)))
        }
    }
}
