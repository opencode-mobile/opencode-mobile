import Foundation

struct Message: Equatable, Sendable, Identifiable {
    let id: String
    let role: MessageRole
    let text: String
    let model: String?
    let mode: String?
    let sentAtEpochMs: Int64?
}

enum MessageRole: String, Sendable {
    case user
    case assistant
    case command
    case tool
    case unknown

    var displayLabel: String {
        switch self {
        case .user:
            "You"
        case .assistant:
            "Assistant"
        case .command:
            "Command"
        case .tool:
            "Tool"
        case .unknown:
            "Message"
        }
    }
}

enum MessageError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case decoding(String)

    var localizedDescription: String {
        switch self {
        case let .transport(message):
            message
        case let .http(_, message):
            message ?? "Request failed"
        case let .decoding(message):
            message
        }
    }
}
