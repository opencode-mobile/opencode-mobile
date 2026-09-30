import Foundation

nonisolated struct Message: Equatable, Sendable, Identifiable {
    let id: String
    let role: MessageRole
    let text: String
    let parts: [MessagePart]
    let cost: Double?
    let tokens: MessageTokenUsage?
    let model: String?
    let modelProviderID: String?
    let modelID: String?
    let variant: String?
    let mode: String?
    let sentAtEpochMs: Int64?
    let completedAtEpochMs: Int64?
    let contextInputTokens: Int64?
    let contextEstimate: MessageContextEstimate
    let followUpDelivery: FollowUpDelivery?
    let isWaitingForPickup: Bool

    init(
        id: String,
        role: MessageRole,
        text: String,
        parts: [MessagePart] = [],
        cost: Double? = nil,
        tokens: MessageTokenUsage? = nil,
        model: String?,
        modelProviderID: String? = nil,
        modelID: String? = nil,
        variant: String? = nil,
        mode: String?,
        sentAtEpochMs: Int64?,
        completedAtEpochMs: Int64? = nil,
        contextInputTokens: Int64? = nil,
        contextEstimate: MessageContextEstimate = MessageContextEstimate(),
        followUpDelivery: FollowUpDelivery? = nil,
        isWaitingForPickup: Bool = false
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.parts = parts
        self.cost = cost
        self.tokens = tokens
        self.model = model
        self.modelProviderID = modelProviderID
        self.modelID = modelID
        self.variant = variant
        self.mode = mode
        self.sentAtEpochMs = sentAtEpochMs
        self.completedAtEpochMs = completedAtEpochMs
        self.contextInputTokens = contextInputTokens
        self.contextEstimate = contextEstimate
        self.followUpDelivery = followUpDelivery
        self.isWaitingForPickup = isWaitingForPickup
    }

    func withPickupState(
        isWaitingForPickup: Bool,
        followUpDelivery: FollowUpDelivery?
    ) -> Message {
        Message(
            id: id,
            role: role,
            text: text,
            parts: parts,
            cost: cost,
            tokens: tokens,
            model: model,
            modelProviderID: modelProviderID,
            modelID: modelID,
            variant: variant,
            mode: mode,
            sentAtEpochMs: sentAtEpochMs,
            completedAtEpochMs: completedAtEpochMs,
            contextInputTokens: contextInputTokens,
            contextEstimate: contextEstimate,
            followUpDelivery: followUpDelivery,
            isWaitingForPickup: isWaitingForPickup
        )
    }
}

nonisolated struct MessageTokenUsage: Equatable, Sendable {
    let input: Int64
    let output: Int64
    let reasoning: Int64
    let cacheRead: Int64
    let cacheWrite: Int64

    var total: Int64 {
        input + output + reasoning + cacheRead + cacheWrite
    }
}

nonisolated struct MessageContextEstimate: Equatable, Sendable {
    let systemCharacters: Int
    let userCharacters: Int
    let assistantCharacters: Int
    let toolCharacters: Int

    init(systemCharacters: Int = 0, userCharacters: Int = 0, assistantCharacters: Int = 0, toolCharacters: Int = 0) {
        self.systemCharacters = systemCharacters
        self.userCharacters = userCharacters
        self.assistantCharacters = assistantCharacters
        self.toolCharacters = toolCharacters
    }
}

nonisolated enum ToolStatus: String, Sendable, Equatable {
    case pending
    case running
    case completed
    case error
}

nonisolated enum MessagePart: Equatable, Sendable {
    case text(TextPart)
    case file(FilePart)
    case tool(ToolPart)
    /// Server compaction marker. Kept for live Compacting… detection; not rendered in timeline.
    case compaction(CompactionPart)
    case reasoning(ReasoningPart)
}

nonisolated struct TextPart: Equatable, Sendable {
    let id: String?
    let text: String
    var isStreaming: Bool
    /// Server-injected context (e.g. decoded text-file attachment body). Hidden in UI.
    let isSynthetic: Bool
    /// Server-marked non-display text. Hidden in UI.
    let isIgnored: Bool

    var isDisplayable: Bool { !isSynthetic && !isIgnored }

    init(
        id: String? = nil,
        text: String,
        isStreaming: Bool,
        isSynthetic: Bool = false,
        isIgnored: Bool = false
    ) {
        self.id = id
        self.text = text
        self.isStreaming = isStreaming
        self.isSynthetic = isSynthetic
        self.isIgnored = isIgnored
    }
}

nonisolated enum AttachmentKind: String, Equatable, Sendable {
    case image
    case pdf
    case text
}

nonisolated struct Attachment: Equatable, Sendable, Identifiable {
    let id: String
    let filename: String
    let mime: String
    let kind: AttachmentKind
    let dataURL: String
    let fileURL: URL?
    let byteCount: Int64
    let previewData: Data?

    var usesFileBackedBody: Bool { fileURL != nil }

    init(
        id: String,
        filename: String,
        mime: String,
        kind: AttachmentKind,
        dataURL: String,
        fileURL: URL? = nil,
        byteCount: Int64 = 0,
        previewData: Data? = nil
    ) {
        self.id = id
        self.filename = filename
        self.mime = mime
        self.kind = kind
        self.dataURL = dataURL
        self.fileURL = fileURL
        self.byteCount = byteCount
        self.previewData = previewData
    }
}

nonisolated struct FilePart: Equatable, Sendable {
    let id: String
    let filename: String
    let mime: String
    let kind: AttachmentKind
    let dataURL: String
    let previewData: Data?

    init(
        id: String,
        filename: String,
        mime: String,
        kind: AttachmentKind,
        dataURL: String,
        previewData: Data? = nil
    ) {
        self.id = id
        self.filename = filename
        self.mime = mime
        self.kind = kind
        self.dataURL = dataURL
        self.previewData = previewData
    }
}

nonisolated struct ToolPart: Equatable, Sendable {
    let id: String?
    let toolName: String
    let status: ToolStatus
    let title: String?
    let error: String?
    let callID: String?
    let input: [String: AnyCodable]?
    let output: String?
    let metadata: [String: AnyCodable]?
    let timeStartEpochMs: Int64?

    init(
        id: String? = nil,
        toolName: String,
        status: ToolStatus,
        title: String? = nil,
        error: String? = nil,
        callID: String? = nil,
        input: [String: AnyCodable]? = nil,
        output: String? = nil,
        metadata: [String: AnyCodable]? = nil,
        timeStartEpochMs: Int64? = nil
    ) {
        self.id = id
        self.toolName = toolName
        self.status = status
        self.title = title
        self.error = error
        self.callID = callID
        self.input = input
        self.output = output
        self.metadata = metadata
        self.timeStartEpochMs = timeStartEpochMs
    }

    func withTimeStart(_ timeStartEpochMs: Int64?) -> ToolPart {
        ToolPart(
            id: id,
            toolName: toolName,
            status: status,
            title: title,
            error: error,
            callID: callID,
            input: input,
            output: output,
            metadata: metadata,
            timeStartEpochMs: timeStartEpochMs
        )
    }
}

nonisolated struct ReasoningPart: Equatable, Sendable {
    let id: String?
    let text: String
    var isStreaming: Bool
    let timeStartEpochMs: Int64?
    let timeEndEpochMs: Int64?

    init(
        id: String? = nil,
        text: String,
        isStreaming: Bool = false,
        timeStartEpochMs: Int64? = nil,
        timeEndEpochMs: Int64? = nil
    ) {
        self.id = id
        self.text = text
        self.isStreaming = isStreaming
        self.timeStartEpochMs = timeStartEpochMs
        self.timeEndEpochMs = timeEndEpochMs
    }
}

nonisolated struct CompactionPart: Equatable, Sendable {
    let id: String?
    let auto: Bool
    let completed: Bool

    init(id: String? = nil, auto: Bool = false, completed: Bool = false) {
        self.id = id
        self.auto = auto
        self.completed = completed
    }
}

nonisolated struct PromptSendOptions: Equatable, Sendable {
    let text: String
    let attachments: [Attachment]
    let model: PromptModelSelection?
    let variant: String?
    let mode: String?
    let agentMentions: [AgentMention]
    let delivery: FollowUpDelivery

    init(
        text: String,
        attachments: [Attachment] = [],
        model: PromptModelSelection? = nil,
        variant: String? = nil,
        mode: String? = nil,
        agentMentions: [AgentMention] = [],
        delivery: FollowUpDelivery = .steer
    ) {
        self.text = text
        self.attachments = attachments
        self.model = model
        self.variant = variant
        self.mode = mode
        self.agentMentions = agentMentions
        self.delivery = delivery
    }
}

nonisolated struct PromptModelSelection: Equatable, Sendable {
    let providerID: String
    let modelID: String
}

nonisolated enum MessageRole: String, Sendable {
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

nonisolated enum MessageError: Error, Equatable {
    case transport(TransportFailure)
    case http(statusCode: Int, message: String?)
    case decoding(String)

    var localizedDescription: String {
        switch self {
        case let .transport(detail):
            detail.message
        case let .http(_, message):
            message ?? "Request failed"
        case let .decoding(message):
            message
        }
    }
}
