import Foundation

public nonisolated enum MessageError: Error, Equatable {
    case transport(TransportFailure)
    case http(statusCode: Int, message: String?)
    case decoding(String)

    /// Diagnostic text for logs. The kit has no app text: an app maps `.http(_, nil)` to its
    /// own localised fallback (OpenCode Mobile's `MessageError.displayMessage`).
    public var localizedDescription: String {
        switch self {
        case let .transport(detail):
            detail.message
        case let .http(statusCode, message):
            message ?? "HTTP \(statusCode)"
        case let .decoding(message):
            message
        }
    }
}
