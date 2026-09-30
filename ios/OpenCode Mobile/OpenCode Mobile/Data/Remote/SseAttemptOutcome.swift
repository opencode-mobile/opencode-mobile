import Foundation

nonisolated enum SseAttemptOutcome: Sendable {
    case ended
    case http(Int)
    case transport(Error?)
}

nonisolated struct SseHandshakeError: Error, Sendable, Equatable {
    enum Reason: String, Sendable {
        case timeout
        case unexpectedFirstFrame
    }

    let reason: Reason
}

/// Headers did not arrive within the connect budget. Not a first-frame handshake timeout.
nonisolated struct SseConnectBudgetExceeded: Error, Sendable, Equatable {}
