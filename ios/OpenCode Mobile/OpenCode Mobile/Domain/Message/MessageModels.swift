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
    /// Why an assistant reply ended early; nil for a complete (or still running) reply.
    var stopReason: MessageStopReason?
    /// A run failure to show under the reply. Nil when the run did not fail, or the user stopped it.
    var failure: SessionFailure?

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
        isWaitingForPickup: Bool = false,
        stopReason: MessageStopReason? = nil,
        failure: SessionFailure? = nil
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
        self.stopReason = stopReason
        self.failure = failure
    }

    /// Fills agent, model and variant this copy lacks from `base` (v2 live infos are partial).
    func withMetadata(from base: Message?) -> Message {
        guard let base else { return self }
        var copy = self
        if copy.stopReason == nil { copy.stopReason = base.stopReason }
        guard mode == nil || model == nil else { return copy }
        let takeModel = model == nil
        return Message(
            id: id,
            role: role,
            text: text,
            parts: parts,
            cost: cost,
            tokens: tokens,
            model: takeModel ? base.model : model,
            modelProviderID: takeModel ? base.modelProviderID : modelProviderID,
            modelID: takeModel ? base.modelID : modelID,
            variant: takeModel ? base.variant : variant,
            mode: mode ?? base.mode,
            sentAtEpochMs: sentAtEpochMs,
            completedAtEpochMs: completedAtEpochMs,
            contextInputTokens: contextInputTokens,
            contextEstimate: contextEstimate,
            followUpDelivery: followUpDelivery,
            isWaitingForPickup: isWaitingForPickup,
            stopReason: copy.stopReason,
            failure: failure
        )
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
            isWaitingForPickup: isWaitingForPickup,
            stopReason: stopReason,
            failure: failure
        )
    }
}

enum FailureSource: Equatable, Sendable {
    case step
    case execution
    case compaction
    case tool
    case http
}

enum FailureAction: Equatable, Sendable {
    case pickModel
    case compact
    case copy
}

/// A failed run, shared by v1 `{name, data}` and v2 `{type, message, status, response}`.
/// Abort and interrupt types are not failures: the timeline shows them as an interrupted note.
nonisolated struct SessionFailure: Equatable, Sendable {
    let type: String?
    let message: String
    let status: Int?
    let providerBody: String?
    var isRetryable: Bool? = nil
    var providerID: String? = nil
    var retries: Int? = nil
    var source: FailureSource = .step

    /// The composer retry status already shows this failure, so the transcript mark waits.
    func coversLiveRetry() -> Bool {
        let kind = type?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        return kind == "provider.rate-limit" || kind == "provider.quota"
    }

    /// An action in the provider sheet. Rate-limit and quota use the composer status instead.
    func action() -> FailureAction? {
        let kind = type?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        if kind == "provider.no-route" { return .pickModel }
        if kind == "provider.content-filter" { return nil }
        if isContextOverflow() { return .compact }
        if kind == "unknown" || kind == "provider.unknown" { return .copy }
        return nil
    }

    func actionLabel() -> String? {
        switch action() {
        case .pickModel: "Pick a model"
        case .compact: "Compact"
        case .copy: "Copy"
        case nil: nil
        }
    }

    /// v1 `ContextOverflowError`, or a provider request the body says overflowed the context.
    func isContextOverflow() -> Bool {
        if type?.caseInsensitiveCompare("ContextOverflowError") == .orderedSame { return true }
        guard type?.caseInsensitiveCompare("provider.invalid-request") == .orderedSame else { return false }
        let haystack = "\(message) \(providerBody ?? "")".lowercased()
        return haystack.contains("context")
            && (haystack.contains("overflow") || haystack.contains("too long") || haystack.contains("length"))
    }

    var isNeutral: Bool {
        type?.caseInsensitiveCompare("permission.rejected") == .orderedSame
    }

    /// Caption under the reply. The provider body stays behind the existing detail sheet.
    func line() -> String {
        let shown = message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? "Session work failed"
            : message
        if let status { return "HTTP \(status) · \(shown)" }
        return shown
    }

    static func isSilent(type: String?) -> Bool {
        let value = type?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.caseInsensitiveCompare("aborted") == .orderedSame
            || value.range(of: "interrupt", options: .caseInsensitive) != nil
            || value == "MessageAbortedError"
    }

    /// A tool call the server refused to execute, as opposed to a tool that ran and failed.
    static func isMalformedTool(type: String?, executed: Bool?) -> Bool {
        executed == false || type == "tool.input-json" || type == "tool.input-incomplete"
    }

    /// Upstream `unwrapErrorMessage`: a JSON envelope `{type: "message", message}` is the text inside it.
    static func unwrap(_ raw: String?) -> String {
        let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard trimmed.hasPrefix("{"),
              let data = trimmed.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (root["type"] as? String) == "message",
              let nested = (root["message"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !nested.isEmpty
        else { return trimmed }
        return unwrap(nested)
    }

    static func fromV1(
        name: String?,
        message: String?,
        statusCode: Int?,
        responseBody: String?,
        isRetryable: Bool? = nil,
        providerID: String? = nil,
        retries: Int? = nil,
        source: FailureSource = .step
    ) -> SessionFailure? {
        if isSilent(type: name) { return nil }
        return build(
            type: name,
            message: message,
            status: statusCode,
            providerBody: responseBody,
            emptyFallback: name == "ContextOverflowError" ? "Context overflow" : "",
            isRetryable: isRetryable,
            providerID: providerID,
            retries: retries,
            source: source
        )
    }

    static func fromV2(
        type: String?,
        message: String?,
        status: Int?,
        providerBody: String?,
        outcome: String? = nil,
        source: FailureSource = .step
    ) -> SessionFailure? {
        if outcome?.caseInsensitiveCompare("succeeded") == .orderedSame
            || outcome?.caseInsensitiveCompare("interrupted") == .orderedSame
        {
            return nil
        }
        if isSilent(type: type) { return nil }
        return build(
            type: type,
            message: message,
            status: status,
            providerBody: providerBody,
            emptyFallback: "",
            source: source
        )
    }

    private static let maxBody = 4_000

    private static func build(
        type: String?,
        message: String?,
        status: Int?,
        providerBody: String?,
        emptyFallback: String,
        isRetryable: Bool? = nil,
        providerID: String? = nil,
        retries: Int? = nil,
        source: FailureSource = .step
    ) -> SessionFailure? {
        let trimmedBody = providerBody?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let body = trimmedBody.isEmpty ? nil : String(trimmedBody.prefix(maxBody))
        var text = unwrap(message)
        if text.isEmpty { text = emptyFallback }
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let first = body?.split(whereSeparator: \.isNewline).first.map(String.init) ?? ""
            text = String(first.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200))
        }
        let trimmedType = type?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let shown = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? trimmedType : text
        let http = status.flatMap { (100...599).contains($0) ? $0 : nil }
        if shown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && body == nil && http == nil {
            return nil
        }
        return SessionFailure(
            type: trimmedType.isEmpty ? nil : trimmedType,
            message: shown,
            status: http,
            providerBody: body,
            isRetryable: isRetryable,
            providerID: providerID,
            retries: retries,
            source: source
        )
    }
}

/// An assistant reply that ended before the model finished its answer.
nonisolated enum MessageStopReason: String, Equatable, Sendable {
    /// The model hit its output limit (`finish: "length"`).
    case length
    /// The user stopped the run (`error.type: "aborted"` / `MessageAbortedError`).
    case interrupted

    /// `finish` is the step finish reason (`"aborted"` is how the v2 live projection
    /// marks an aborted step); `errorKind` is the v2 error `type` or v1 error `name`.
    static func from(finish: String?, errorKind: String?) -> MessageStopReason? {
        switch finish?.lowercased() {
        case "length": return .length
        case "aborted": return .interrupted
        default:
            return ToolInterruption.isUserAbortError(type: errorKind, name: errorKind) ? .interrupted : nil
        }
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
    /// Server compaction marker. Drives live Compacting… detection; on an
    /// assistant (v2) message it renders as a compaction row whose summary stays
    /// behind a disclosure, never as reply text.
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
    /// Failed because the user stopped the run or declined the call, not a real failure.
    let isInterrupted: Bool
    /// The model emitted a tool call the server could not run (`executed: false`).
    let isMalformed: Bool

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
        timeStartEpochMs: Int64? = nil,
        isInterrupted: Bool = false,
        isMalformed: Bool = false
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
        self.isInterrupted = isInterrupted
        self.isMalformed = isMalformed
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
            timeStartEpochMs: timeStartEpochMs,
            isInterrupted: isInterrupted,
            isMalformed: isMalformed
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
    let summary: String?

    init(id: String? = nil, auto: Bool = false, completed: Bool = false, summary: String? = nil) {
        self.id = id
        self.auto = auto
        self.completed = completed
        self.summary = summary
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
