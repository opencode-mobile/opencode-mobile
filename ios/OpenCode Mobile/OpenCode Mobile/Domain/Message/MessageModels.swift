import Foundation

/// A skill admitted on a v2 user prompt. The skill body stays on the server;
/// chat only needs the id and slash name so the prompt can show as `[/name]`.
nonisolated struct PromptSkill: Equatable, Sendable {
    let id: String
    let name: String
    /// Mention text already present in the prompt, when another client recorded one.
    let mentionText: String?
    /// Offsets of that mention into the prompt text, when the sender recorded them.
    let mentionStart: Int?
    let mentionEnd: Int?

    init(id: String, name: String, mentionText: String? = nil, mentionStart: Int? = nil, mentionEnd: Int? = nil) {
        self.id = id
        self.name = name
        self.mentionText = mentionText
        self.mentionStart = mentionStart
        self.mentionEnd = mentionEnd
    }
}

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
    /// Skills admitted with this prompt. Empty for v1 and for messages that did not load a skill.
    var promptSkills: [PromptSkill]

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
        failure: SessionFailure? = nil,
        promptSkills: [PromptSkill] = []
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
        self.promptSkills = promptSkills
    }

    /// Fills agent, model, variant, prompt skills, and an omitted failure this copy lacks from `base`.
    /// v2 live infos are partial. An explicit null error is a clear and must not be restored.
    func withMetadata(from base: Message?, failureOmitted: Bool) -> Message {
        guard let base else { return self }
        var copy = self
        if copy.stopReason == nil { copy.stopReason = base.stopReason }
        if copy.failure == nil, failureOmitted { copy.failure = base.failure }
        if copy.promptSkills.isEmpty { copy.promptSkills = base.promptSkills }
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
            failure: copy.failure,
            promptSkills: copy.promptSkills
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
            failure: failure,
            promptSkills: promptSkills
        )
    }

    func withParts(_ parts: [MessagePart]) -> Message {
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
            failure: failure,
            promptSkills: promptSkills
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
    /// v2 `retryAfterMs`: how long the provider asked to wait.
    var retryAfterMs: Int64? = nil
    /// When the failure was recorded; set only with `retryAfterMs`.
    var recordedAtEpochMs: Int64? = nil

    /// When the provider said to try again; nil when the server sent no `retryAfterMs`
    /// or one too large to add (Int64 addition traps on overflow).
    func retryAtEpochMs() -> Int64? {
        guard let retryAfterMs, let recordedAtEpochMs else { return nil }
        let (retryAt, overflow) = recordedAtEpochMs.addingReportingOverflow(retryAfterMs)
        return overflow ? nil : retryAt
    }

    /// "Try again in N s", minutes above 90 s; nil once the wait is over.
    static func retryAfterLabel(remainingMs: Int64) -> String? {
        guard remainingMs > 0 else { return nil }
        if remainingMs <= 90_000 { return "Try again in \(ceilDiv(remainingMs, 1_000)) s" }
        return "Try again in \(ceilDiv(remainingMs, 60_000)) min"
    }

    private static func ceilDiv(_ value: Int64, _ unit: Int64) -> Int64 {
        (value + unit - 1) / unit
    }

    /// The composer retry status already shows this failure, so the transcript mark waits.
    func coversLiveRetry() -> Bool {
        let kind = type?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        return kind == "provider.rate-limit" || kind == "provider.quota"
    }

    /// An action in the provider sheet. Rate-limit and quota use the composer status instead.
    func action() -> FailureAction? {
        let kind = type?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        if kind == "provider.no-route" { return .pickModel }
        if kind == "provider.free-limit" || kind == "provider.model-budget-limit" { return .pickModel }
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
        source: FailureSource = .step,
        retryAfterMs: Int64? = nil,
        recordedAtEpochMs: Int64? = nil
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
            source: source,
            retryAfterMs: retryAfterMs,
            recordedAtEpochMs: recordedAtEpochMs
        )
    }

    static func fromV2(
        type: String?,
        message: String?,
        status: Int?,
        providerBody: String?,
        outcome: String? = nil,
        source: FailureSource = .step,
        retryAfterMs: Int64? = nil,
        recordedAtEpochMs: Int64? = nil
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
            source: source,
            retryAfterMs: retryAfterMs,
            recordedAtEpochMs: recordedAtEpochMs
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
        source: FailureSource = .step,
        retryAfterMs: Int64? = nil,
        recordedAtEpochMs: Int64? = nil
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
        let wait = retryAfterMs.flatMap { $0 >= 0 ? $0 : nil }
        return SessionFailure(
            type: trimmedType.isEmpty ? nil : trimmedType,
            message: shown,
            status: http,
            providerBody: body,
            isRetryable: isRetryable,
            providerID: providerID,
            retries: retries,
            source: source,
            retryAfterMs: wait,
            recordedAtEpochMs: wait == nil ? nil : recordedAtEpochMs
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
    /// A session-level change recorded between turns. Only on a `.notice` message.
    case notice(TimelineNotice)
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
    /// Anything else, which only travels as a file uploaded to the server.
    case file
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
    /// Absolute path on the server when the bytes were uploaded (`metadata.attachments`).
    let serverPath: String?

    init(
        id: String,
        filename: String,
        mime: String,
        kind: AttachmentKind,
        dataURL: String,
        previewData: Data? = nil,
        serverPath: String? = nil
    ) {
        self.id = id
        self.filename = filename
        self.mime = mime
        self.kind = kind
        self.dataURL = dataURL
        self.previewData = previewData
        self.serverPath = serverPath
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

    func withMetadataValue(_ value: AnyCodable, forKey key: String) -> ToolPart {
        var metadata = metadata ?? [:]
        metadata[key] = value
        return ToolPart(
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

/// A session-level change the timeline shows as a quiet line where it happened:
/// the working directory moved, the agent or model changed, instructions were
/// updated, or the server resumed a turn after a restart. Only v2 records these.
nonisolated struct TimelineNotice: Equatable, Sendable {
    /// The server message id; a live notice and its REST copy share it.
    let id: String
    let kind: TimelineNoticeKind
}

nonisolated enum TimelineNoticeKind: Equatable, Sendable {
    /// The session's working directory moved.
    case moved(directory: String)
    /// The primary agent changed. `previous` is nil when unknown or unchanged.
    case agentChanged(agent: String, previous: String?)
    /// The model or its variant changed. `previous` is nil when unknown (live).
    case modelChanged(model: TimelineNoticeModel, previous: TimelineNoticeModel?)
    /// Instruction files changed; `sources` may be empty when the server named none.
    case instructionsUpdated(sources: [String])
    /// Any other system update, by its human-readable description.
    case systemNote(String)
    /// The server restarted mid-turn and resumed it.
    case restart
}

nonisolated struct TimelineNoticeModel: Equatable, Sendable {
    let providerID: String?
    let modelID: String
    let variant: String?

    init(providerID: String?, modelID: String, variant: String? = nil) {
        self.providerID = providerID
        self.modelID = modelID
        self.variant = variant
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
    let fileMentions: [FileMention]
    /// Server worktree used to build `file://` URLs for file mentions.
    let worktree: String?
    /// v2 skills placed as `/name` spans; sent with their offsets into `text`.
    let skillMentions: [SkillMention]
    let delivery: FollowUpDelivery
    /// `ClientMessageID` the server gives the user message it creates.
    let messageID: String?

    init(
        text: String,
        attachments: [Attachment] = [],
        model: PromptModelSelection? = nil,
        variant: String? = nil,
        mode: String? = nil,
        agentMentions: [AgentMention] = [],
        fileMentions: [FileMention] = [],
        worktree: String? = nil,
        skillMentions: [SkillMention] = [],
        delivery: FollowUpDelivery = .steer,
        messageID: String? = nil
    ) {
        self.text = text
        self.attachments = attachments
        self.model = model
        self.variant = variant
        self.mode = mode
        self.agentMentions = agentMentions
        self.fileMentions = fileMentions
        self.worktree = worktree
        self.skillMentions = skillMentions
        self.delivery = delivery
        self.messageID = messageID
    }
}

nonisolated struct PromptModelSelection: Equatable, Sendable {
    let providerID: String
    let modelID: String
}

/// A shell command typed as `!command` in the composer. v1 records the run under `agent` and
/// `model`; v2 runs it in the session as it is and ignores both.
nonisolated struct ShellCommandOptions: Equatable, Sendable {
    let command: String
    let agent: String
    let model: PromptModelSelection?
}

nonisolated enum MessageRole: String, Sendable {
    case user
    case assistant
    case command
    case tool
    /// A timeline notice: neither the user's prompt nor a reply.
    case notice
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
        case .notice:
            "Notice"
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
