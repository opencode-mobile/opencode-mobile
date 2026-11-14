import Foundation

public nonisolated enum SseAttemptOutcome: Sendable {
    case ended
    case http(Int)
    case transport(Error?)
}

public nonisolated struct SseHandshakeError: Error, Sendable, Equatable {
    public enum Reason: String, Sendable {
        case timeout
        case unexpectedFirstFrame
    }

    public let reason: Reason

    public init(reason: Reason) {
        self.reason = reason
    }
}

/// Headers did not arrive within the connect budget. Not a first-frame handshake timeout.
public nonisolated struct SseConnectBudgetExceeded: Error, Sendable, Equatable {
    public init() {}
}
