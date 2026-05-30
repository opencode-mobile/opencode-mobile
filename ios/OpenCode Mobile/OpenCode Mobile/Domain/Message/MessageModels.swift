import Foundation

struct Message: Equatable, Sendable, Identifiable {
    let id: String
    let role: MessageRole
    let text: String
    let model: String?
    let modelProviderID: String?
    let modelID: String?
    let variant: String?
    let mode: String?
    let sentAtEpochMs: Int64?

    init(
        id: String,
        role: MessageRole,
        text: String,
        model: String?,
        modelProviderID: String? = nil,
        modelID: String? = nil,
        variant: String? = nil,
        mode: String?,
        sentAtEpochMs: Int64?
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.model = model
        self.modelProviderID = modelProviderID
        self.modelID = modelID
        self.variant = variant
        self.mode = mode
        self.sentAtEpochMs = sentAtEpochMs
    }
}

struct PromptSendOptions: Equatable, Sendable {
    let text: String
    let model: PromptModelSelection?
    let variant: String?
}

struct PromptModelSelection: Equatable, Sendable {
    let providerID: String
    let modelID: String
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
