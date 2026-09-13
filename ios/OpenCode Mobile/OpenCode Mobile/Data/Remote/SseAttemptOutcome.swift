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
