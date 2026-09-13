import Foundation

nonisolated enum SseAttemptPolicy {
    enum Decision: Equatable, Sendable {
        case retry(afterMs: Int)
        case terminal(EventStreamConnectionState)
    }

    static let unconfirmedNotFoundRetryMs = 1_000

    static func decide(
        outcome: SseAttemptOutcome,
        protocolConfirmed: Bool,
        attempt: Int,
        delayMs: (Int) -> Int = { SseRetryBackoff.delayMs(attempt: $0) }
    ) -> Decision {
        switch outcome {
        case .http(401), .http(403):
            .terminal(.authFailed)
        case let .http(status) where status == 404 && !protocolConfirmed:
            .retry(afterMs: unconfirmedNotFoundRetryMs)
        case .ended, .http, .transport:
            .retry(afterMs: delayMs(max(attempt, 1)))
        }
    }
}
