import Foundation

nonisolated enum SessionCacheCodec {
    static let schemaVersion = 1

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        return decoder
    }()

    static func encodeSessions(_ sessions: [Session]) throws -> String {
        let envelope = CachedEnvelope(
            schemaVersion: schemaVersion,
            sessions: CachedSessionListPayload(sessions: sessions.map { $0.toCachePayload() }),
            messages: nil,
            activity: nil
        )
        let data = try encoder.encode(envelope)
        guard let string = String(data: data, encoding: .utf8) else {
            throw SessionCacheCodecError.utf8EncodingFailed
        }
        return string
    }

    static func decodeSessions(_ raw: String) -> [Session]? {
        guard let data = raw.data(using: .utf8) else { return nil }
        do {
            let envelope = try decoder.decode(CachedEnvelope.self, from: data)
            guard envelope.schemaVersion == schemaVersion else {
                DiagnosticLog.warning(.session, "session list schema mismatch: \(envelope.schemaVersion)")
                return nil
            }
            guard let sessions = envelope.sessions?.sessions else {
                DiagnosticLog.warning(.session, "session list decode failed: missing sessions payload")
                return nil
            }
            return sessions.map { $0.toDomain() }
        } catch {
            DiagnosticLog.warning(.session, "session list decode failed: \(error.localizedDescription)")
            return nil
        }
    }

    static func encodeMessages(_ messages: [Message]) throws -> String {
        let envelope = CachedEnvelope(
            schemaVersion: schemaVersion,
            sessions: nil,
            messages: CachedMessageListPayload(messages: messages.map { $0.toCachePayload() }),
            activity: nil
        )
        let data = try encoder.encode(envelope)
        guard let string = String(data: data, encoding: .utf8) else {
            throw SessionCacheCodecError.utf8EncodingFailed
        }
        return string
    }

    static func decodeMessages(_ raw: String) -> [Message]? {
        guard let data = raw.data(using: .utf8) else { return nil }
        do {
            let envelope = try decoder.decode(CachedEnvelope.self, from: data)
            guard envelope.schemaVersion == schemaVersion else {
                DiagnosticLog.warning(.session, "message schema mismatch: \(envelope.schemaVersion)")
                return nil
            }
            guard let messages = envelope.messages?.messages else {
                DiagnosticLog.warning(.session, "message decode failed: missing messages payload")
                return nil
            }
            return messages.map { $0.toDomain() }
        } catch {
            DiagnosticLog.warning(.session, "message decode failed: \(error.localizedDescription)")
            return nil
        }
    }

    static func encodeActivity(_ entries: [SessionActivityCacheEntry]) throws -> String {
        let envelope = CachedEnvelope(
            schemaVersion: schemaVersion,
            sessions: nil,
            messages: nil,
            activity: CachedActivityListPayload(entries: entries.map { $0.toCachePayload() })
        )
        let data = try encoder.encode(envelope)
        guard let string = String(data: data, encoding: .utf8) else {
            throw SessionCacheCodecError.utf8EncodingFailed
        }
        return string
    }

    static func decodeActivity(_ raw: String) -> [SessionActivityCacheEntry]? {
        guard let data = raw.data(using: .utf8) else { return nil }
        do {
            let envelope = try decoder.decode(CachedEnvelope.self, from: data)
            guard envelope.schemaVersion == schemaVersion else {
                DiagnosticLog.warning(.session, "activity schema mismatch: \(envelope.schemaVersion)")
                return nil
            }
            guard let entries = envelope.activity?.entries else {
                DiagnosticLog.warning(.session, "activity decode failed: missing activity payload")
                return nil
            }
            return entries.map { $0.toDomain() }
        } catch {
            DiagnosticLog.warning(.session, "activity decode failed: \(error.localizedDescription)")
            return nil
        }
    }

    static func encodeProjectResolution(_ resolution: HomeProjectResolution) throws -> String {
        let payload = CachedProjectResolutionPayload(
            schemaVersion: schemaVersion,
            id: resolution.id,
            canonical: resolution.canonical,
            name: resolution.name,
            isRepository: resolution.isRepository
        )
        let data = try encoder.encode(payload)
        guard let string = String(data: data, encoding: .utf8) else {
            throw SessionCacheCodecError.utf8EncodingFailed
        }
        return string
    }

    static func decodeProjectResolution(_ raw: String) -> HomeProjectResolution? {
        guard let data = raw.data(using: .utf8),
              let payload = try? decoder.decode(CachedProjectResolutionPayload.self, from: data),
              payload.schemaVersion == schemaVersion
        else {
            DiagnosticLog.warning(.session, "project resolution decode failed")
            return nil
        }
        return HomeProjectResolution(
            id: payload.id,
            canonical: payload.canonical,
            name: payload.name,
            isRepository: payload.isRepository
        )
    }
}

nonisolated enum SessionCacheCodecError: Error {
    case utf8EncodingFailed
}

// MARK: - Envelope

nonisolated private struct CachedEnvelope: Codable, Sendable {
    let schemaVersion: Int
    let sessions: CachedSessionListPayload?
    let messages: CachedMessageListPayload?
    let activity: CachedActivityListPayload?
}

nonisolated private struct CachedProjectResolutionPayload: Codable, Sendable {
    let schemaVersion: Int
    let id: String
    let canonical: String
    let name: String?
    let isRepository: Bool
}

nonisolated private struct CachedSessionListPayload: Codable, Sendable {
    let sessions: [CachedSessionPayload]
}

nonisolated private struct CachedSessionPayload: Codable, Sendable {
    let id: String
    let title: String
    let slug: String?
    let projectId: String?
    let directory: String?
    let parentId: String?
    let cost: Double?
    let tokens: CachedMessageTokenUsagePayload?
    let createdAtEpochMs: Int64?
    let updatedAtEpochMs: Int64?
    let archivedAtEpochMs: Int64?
    let revert: CachedSessionRevertPayload?
    let idleAtEpochMs: Int64?
    let viewedAtEpochMs: Int64?
    let idleOutcome: String?
}

nonisolated private struct CachedSessionRevertPayload: Codable, Sendable {
    let messageId: String
    let partId: String?
    let snapshot: String?
    let diff: String?
}

nonisolated private struct CachedActivityListPayload: Codable, Sendable {
    let entries: [CachedActivityEntryPayload]
}

nonisolated private struct CachedActivityEntryPayload: Codable, Sendable {
    let sessionId: String
    let type: String
    let attempt: Int?
    let observedAtMs: Int64
}

nonisolated private struct CachedMessageListPayload: Codable, Sendable {
    let messages: [CachedMessagePayload]
}

nonisolated private struct CachedMessagePayload: Codable, Sendable {
    let id: String
    let role: String
    let text: String
    let parts: [CachedMessagePartPayload]
    let cost: Double?
    let tokens: CachedMessageTokenUsagePayload?
    let model: String?
    let modelProviderId: String?
    let modelId: String?
    let variant: String?
    let mode: String?
    let sentAtEpochMs: Int64?
    let completedAtEpochMs: Int64?
    let contextInputTokens: Int64?
    let contextEstimate: CachedMessageContextEstimatePayload
    let stopReason: String?
    let failureType: String?
    let failureMessage: String?
    let failureStatus: Int?
    let failureBody: String?
    let failureRetryAfterMs: Int64?
    let failureRecordedAtEpochMs: Int64?

    init(
        id: String,
        role: String,
        text: String,
        parts: [CachedMessagePartPayload] = [],
        cost: Double? = nil,
        tokens: CachedMessageTokenUsagePayload? = nil,
        model: String? = nil,
        modelProviderId: String? = nil,
        modelId: String? = nil,
        variant: String? = nil,
        mode: String? = nil,
        sentAtEpochMs: Int64? = nil,
        completedAtEpochMs: Int64? = nil,
        contextInputTokens: Int64? = nil,
        contextEstimate: CachedMessageContextEstimatePayload = CachedMessageContextEstimatePayload(),
        stopReason: String? = nil,
        failureType: String? = nil,
        failureMessage: String? = nil,
        failureStatus: Int? = nil,
        failureBody: String? = nil,
        failureRetryAfterMs: Int64? = nil,
        failureRecordedAtEpochMs: Int64? = nil
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.parts = parts
        self.cost = cost
        self.tokens = tokens
        self.model = model
        self.modelProviderId = modelProviderId
        self.modelId = modelId
        self.variant = variant
        self.mode = mode
        self.sentAtEpochMs = sentAtEpochMs
        self.completedAtEpochMs = completedAtEpochMs
        self.contextInputTokens = contextInputTokens
        self.contextEstimate = contextEstimate
        self.stopReason = stopReason
        self.failureType = failureType
        self.failureMessage = failureMessage
        self.failureStatus = failureStatus
        self.failureBody = failureBody
        self.failureRetryAfterMs = failureRetryAfterMs
        self.failureRecordedAtEpochMs = failureRecordedAtEpochMs
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        role = try container.decode(String.self, forKey: .role)
        text = try container.decode(String.self, forKey: .text)
        parts = try container.decodeIfPresent([CachedMessagePartPayload].self, forKey: .parts) ?? []
        cost = try container.decodeIfPresent(Double.self, forKey: .cost)
        tokens = try container.decodeIfPresent(CachedMessageTokenUsagePayload.self, forKey: .tokens)
        model = try container.decodeIfPresent(String.self, forKey: .model)
        modelProviderId = try container.decodeIfPresent(String.self, forKey: .modelProviderId)
        modelId = try container.decodeIfPresent(String.self, forKey: .modelId)
        variant = try container.decodeIfPresent(String.self, forKey: .variant)
        mode = try container.decodeIfPresent(String.self, forKey: .mode)
        sentAtEpochMs = try container.decodeIfPresent(Int64.self, forKey: .sentAtEpochMs)
        completedAtEpochMs = try container.decodeIfPresent(Int64.self, forKey: .completedAtEpochMs)
        contextInputTokens = try container.decodeIfPresent(Int64.self, forKey: .contextInputTokens)
        contextEstimate = try container.decodeIfPresent(
            CachedMessageContextEstimatePayload.self,
            forKey: .contextEstimate
        ) ?? CachedMessageContextEstimatePayload()
        stopReason = try container.decodeIfPresent(String.self, forKey: .stopReason)
        failureType = try container.decodeIfPresent(String.self, forKey: .failureType)
        failureMessage = try container.decodeIfPresent(String.self, forKey: .failureMessage)
        failureStatus = try container.decodeIfPresent(Int.self, forKey: .failureStatus)
        failureBody = try container.decodeIfPresent(String.self, forKey: .failureBody)
        failureRetryAfterMs = try container.decodeIfPresent(Int64.self, forKey: .failureRetryAfterMs)
        failureRecordedAtEpochMs = try container.decodeIfPresent(Int64.self, forKey: .failureRecordedAtEpochMs)
    }
}

nonisolated private struct CachedMessagePartPayload: Codable, Sendable {
    let kind: String
    let id: String?
    let text: String?
    let isStreaming: Bool
    let isSynthetic: Bool
    let isIgnored: Bool
    let toolName: String?
    let status: String?
    let title: String?
    let error: String?
    let callId: String?
    let input: [String: AnyCodable]?
    let output: String?
    let metadata: [String: AnyCodable]?
    let filename: String?
    let mime: String?
    let attachmentKind: String?
    let dataUrl: String?
    let serverPath: String?
    let timeStartEpochMs: Int64?
    let timeEndEpochMs: Int64?
    let auto: Bool?
    let isInterrupted: Bool
    let isMalformed: Bool

    init(
        kind: String,
        id: String? = nil,
        text: String? = nil,
        isStreaming: Bool = false,
        isSynthetic: Bool = false,
        isIgnored: Bool = false,
        toolName: String? = nil,
        status: String? = nil,
        title: String? = nil,
        error: String? = nil,
        callId: String? = nil,
        input: [String: AnyCodable]? = nil,
        output: String? = nil,
        metadata: [String: AnyCodable]? = nil,
        filename: String? = nil,
        mime: String? = nil,
        attachmentKind: String? = nil,
        dataUrl: String? = nil,
        serverPath: String? = nil,
        timeStartEpochMs: Int64? = nil,
        timeEndEpochMs: Int64? = nil,
        auto: Bool? = nil,
        isInterrupted: Bool = false,
        isMalformed: Bool = false
    ) {
        self.kind = kind
        self.id = id
        self.text = text
        self.isStreaming = isStreaming
        self.isSynthetic = isSynthetic
        self.isIgnored = isIgnored
        self.toolName = toolName
        self.status = status
        self.title = title
        self.error = error
        self.callId = callId
        self.input = input
        self.output = output
        self.metadata = metadata
        self.filename = filename
        self.mime = mime
        self.attachmentKind = attachmentKind
        self.dataUrl = dataUrl
        self.serverPath = serverPath
        self.timeStartEpochMs = timeStartEpochMs
        self.timeEndEpochMs = timeEndEpochMs
        self.auto = auto
        self.isInterrupted = isInterrupted
        self.isMalformed = isMalformed
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        kind = try container.decode(String.self, forKey: .kind)
        id = try container.decodeIfPresent(String.self, forKey: .id)
        text = try container.decodeIfPresent(String.self, forKey: .text)
        isStreaming = try container.decodeIfPresent(Bool.self, forKey: .isStreaming) ?? false
        isSynthetic = try container.decodeIfPresent(Bool.self, forKey: .isSynthetic) ?? false
        isIgnored = try container.decodeIfPresent(Bool.self, forKey: .isIgnored) ?? false
        toolName = try container.decodeIfPresent(String.self, forKey: .toolName)
        status = try container.decodeIfPresent(String.self, forKey: .status)
        title = try container.decodeIfPresent(String.self, forKey: .title)
        error = try container.decodeIfPresent(String.self, forKey: .error)
        callId = try container.decodeIfPresent(String.self, forKey: .callId)
        input = try container.decodeIfPresent([String: AnyCodable].self, forKey: .input)
        output = try container.decodeIfPresent(String.self, forKey: .output)
        metadata = try container.decodeIfPresent([String: AnyCodable].self, forKey: .metadata)
        filename = try container.decodeIfPresent(String.self, forKey: .filename)
        mime = try container.decodeIfPresent(String.self, forKey: .mime)
        attachmentKind = try container.decodeIfPresent(String.self, forKey: .attachmentKind)
        dataUrl = try container.decodeIfPresent(String.self, forKey: .dataUrl)
        serverPath = try container.decodeIfPresent(String.self, forKey: .serverPath)
        timeStartEpochMs = try container.decodeIfPresent(Int64.self, forKey: .timeStartEpochMs)
        timeEndEpochMs = try container.decodeIfPresent(Int64.self, forKey: .timeEndEpochMs)
        auto = try container.decodeIfPresent(Bool.self, forKey: .auto)
        isInterrupted = try container.decodeIfPresent(Bool.self, forKey: .isInterrupted) ?? false
        isMalformed = try container.decodeIfPresent(Bool.self, forKey: .isMalformed) ?? false
    }
}

nonisolated private struct CachedMessageTokenUsagePayload: Codable, Sendable {
    let input: Int64
    let output: Int64
    let reasoning: Int64
    let cacheRead: Int64
    let cacheWrite: Int64
}

nonisolated private struct CachedMessageContextEstimatePayload: Codable, Sendable {
    let systemCharacters: Int
    let userCharacters: Int
    let assistantCharacters: Int
    let toolCharacters: Int

    init(
        systemCharacters: Int = 0,
        userCharacters: Int = 0,
        assistantCharacters: Int = 0,
        toolCharacters: Int = 0
    ) {
        self.systemCharacters = systemCharacters
        self.userCharacters = userCharacters
        self.assistantCharacters = assistantCharacters
        self.toolCharacters = toolCharacters
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        systemCharacters = try container.decodeIfPresent(Int.self, forKey: .systemCharacters) ?? 0
        userCharacters = try container.decodeIfPresent(Int.self, forKey: .userCharacters) ?? 0
        assistantCharacters = try container.decodeIfPresent(Int.self, forKey: .assistantCharacters) ?? 0
        toolCharacters = try container.decodeIfPresent(Int.self, forKey: .toolCharacters) ?? 0
    }
}

// MARK: - Mapping

private extension Session {
    func toCachePayload() -> CachedSessionPayload {
        CachedSessionPayload(
            id: id,
            title: title,
            slug: slug,
            projectId: projectID,
            directory: directory,
            parentId: parentID,
            cost: cost,
            tokens: tokens?.toCachePayload(),
            createdAtEpochMs: createdAtEpochMs,
            updatedAtEpochMs: updatedAtEpochMs,
            archivedAtEpochMs: archivedAtEpochMs,
            revert: revert?.toCachePayload(),
            idleAtEpochMs: idleAtEpochMs,
            viewedAtEpochMs: viewedAtEpochMs,
            idleOutcome: idleOutcome?.rawValue
        )
    }
}

private extension CachedSessionPayload {
    func toDomain() -> Session {
        Session(
            id: id,
            title: title,
            slug: slug,
            projectID: projectId,
            directory: directory,
            parentID: parentId,
            cost: cost,
            tokens: tokens?.toDomain(),
            createdAtEpochMs: createdAtEpochMs,
            updatedAtEpochMs: updatedAtEpochMs,
            archivedAtEpochMs: archivedAtEpochMs,
            revert: revert?.toDomain(),
            idleAtEpochMs: idleAtEpochMs,
            viewedAtEpochMs: viewedAtEpochMs,
            idleOutcome: SessionIdleOutcome(wireValue: idleOutcome)
        )
    }
}

private extension SessionRevert {
    func toCachePayload() -> CachedSessionRevertPayload {
        CachedSessionRevertPayload(
            messageId: messageID,
            partId: partID,
            snapshot: snapshot,
            diff: diff
        )
    }
}

private extension CachedSessionRevertPayload {
    func toDomain() -> SessionRevert {
        SessionRevert(
            messageID: messageId,
            partID: partId,
            snapshot: snapshot,
            diff: diff
        )
    }
}

private extension SessionActivityCacheEntry {
    func toCachePayload() -> CachedActivityEntryPayload {
        let type: String
        let attempt: Int?
        switch activity {
        case .idle:
            type = "idle"
            attempt = nil
        case .running:
            type = "running"
            attempt = nil
        case let .retrying(value, _, _, _, _, _):
            type = "retry"
            attempt = value
        }
        return CachedActivityEntryPayload(
            sessionId: sessionId,
            type: type,
            attempt: attempt,
            observedAtMs: observedAtMs
        )
    }
}

private extension CachedActivityEntryPayload {
    func toDomain() -> SessionActivityCacheEntry {
        let activity: SessionActivity
        switch type.lowercased() {
        case "busy", "running":
            activity = .running
        case "retry":
            activity = .retrying(
                attempt: attempt,
                nextAtMs: nil,
                message: nil,
                errorType: nil,
                actionLabel: nil,
                actionLink: nil
            )
        default:
            activity = .idle
        }
        return SessionActivityCacheEntry(
            sessionId: sessionId,
            activity: activity,
            observedAtMs: observedAtMs
        )
    }
}

private extension Message {
    func toCachePayload() -> CachedMessagePayload {
        CachedMessagePayload(
            id: id,
            role: role.rawValue,
            text: text,
            parts: parts.compactMap { $0.toCachePayload() },
            cost: cost,
            tokens: tokens?.toCachePayload(),
            model: model,
            modelProviderId: modelProviderID,
            modelId: modelID,
            variant: variant,
            mode: mode,
            sentAtEpochMs: sentAtEpochMs,
            completedAtEpochMs: completedAtEpochMs,
            contextInputTokens: contextInputTokens,
            contextEstimate: contextEstimate.toCachePayload(),
            stopReason: stopReason?.rawValue,
            failureType: failure?.type,
            failureMessage: failure?.message,
            failureStatus: failure?.status,
            failureBody: failure?.providerBody,
            failureRetryAfterMs: failure?.retryAfterMs,
            failureRecordedAtEpochMs: failure?.recordedAtEpochMs
        )
    }
}

private extension CachedMessagePayload {
    func toDomain() -> Message {
        Message(
            id: id,
            role: MessageRole(rawValue: role.lowercased()) ?? .unknown,
            text: text,
            parts: parts.compactMap { $0.toDomain() },
            cost: cost,
            tokens: tokens?.toDomain(),
            model: model,
            modelProviderID: modelProviderId,
            modelID: modelId,
            variant: variant,
            mode: mode,
            sentAtEpochMs: sentAtEpochMs,
            completedAtEpochMs: completedAtEpochMs,
            contextInputTokens: contextInputTokens,
            contextEstimate: contextEstimate.toDomain(),
            stopReason: stopReason.flatMap(MessageStopReason.init(rawValue:)),
            failure: (failureMessage != nil || failureType != nil || failureBody != nil || failureStatus != nil)
                ? SessionFailure(
                    type: failureType,
                    message: failureMessage ?? "",
                    status: failureStatus,
                    providerBody: failureBody,
                    retryAfterMs: failureRetryAfterMs,
                    recordedAtEpochMs: failureRecordedAtEpochMs
                )
                : nil
        )
    }
}

private extension MessageTokenUsage {
    func toCachePayload() -> CachedMessageTokenUsagePayload {
        CachedMessageTokenUsagePayload(
            input: input,
            output: output,
            reasoning: reasoning,
            cacheRead: cacheRead,
            cacheWrite: cacheWrite
        )
    }
}

private extension CachedMessageTokenUsagePayload {
    func toDomain() -> MessageTokenUsage {
        MessageTokenUsage(
            input: input,
            output: output,
            reasoning: reasoning,
            cacheRead: cacheRead,
            cacheWrite: cacheWrite
        )
    }
}

private extension MessageContextEstimate {
    func toCachePayload() -> CachedMessageContextEstimatePayload {
        CachedMessageContextEstimatePayload(
            systemCharacters: systemCharacters,
            userCharacters: userCharacters,
            assistantCharacters: assistantCharacters,
            toolCharacters: toolCharacters
        )
    }
}

private extension CachedMessageContextEstimatePayload {
    func toDomain() -> MessageContextEstimate {
        MessageContextEstimate(
            systemCharacters: systemCharacters,
            userCharacters: userCharacters,
            assistantCharacters: assistantCharacters,
            toolCharacters: toolCharacters
        )
    }
}

private extension MessagePart {
    func toCachePayload() -> CachedMessagePartPayload? {
        switch self {
        case let .text(part):
            return CachedMessagePartPayload(
                kind: "text",
                id: part.id,
                text: part.text,
                isStreaming: part.isStreaming,
                isSynthetic: part.isSynthetic,
                isIgnored: part.isIgnored
            )
        case let .tool(part):
            return CachedMessagePartPayload(
                kind: "tool",
                id: part.id,
                toolName: part.toolName,
                status: part.status.rawValue,
                title: part.title,
                error: part.error,
                callId: part.callID,
                input: part.input,
                output: part.output,
                metadata: part.metadata,
                timeStartEpochMs: part.timeStartEpochMs,
                isInterrupted: part.isInterrupted,
                isMalformed: part.isMalformed
            )
        case let .file(part):
            return CachedMessagePartPayload(
                kind: "file",
                id: part.id,
                filename: part.filename,
                mime: part.mime,
                attachmentKind: part.kind.rawValue,
                dataUrl: AttachmentLimits.persistedDataURL(part.dataURL),
                serverPath: part.serverPath
            )
        case let .compaction(part):
            return CachedMessagePartPayload(
                kind: "compaction",
                id: part.id,
                text: part.summary,
                status: part.completed ? "completed" : "running",
                auto: part.auto
            )
        case let .reasoning(part):
            return CachedMessagePartPayload(
                kind: "reasoning",
                id: part.id,
                text: part.text,
                isStreaming: part.isStreaming,
                timeStartEpochMs: part.timeStartEpochMs,
                timeEndEpochMs: part.timeEndEpochMs
            )
        case let .notice(notice):
            return CachedMessagePartPayload(kind: "notice", id: notice.id, metadata: notice.kind.cacheFields)
        }
    }
}

private extension TimelineNoticeKind {
    var cacheFields: [String: AnyCodable] {
        func optional(_ value: String?) -> AnyCodable { value.map(AnyCodable.string) ?? .null }
        switch self {
        case let .moved(directory):
            return ["notice": .string("moved"), "directory": .string(directory)]
        case let .agentChanged(agent, previous):
            return ["notice": .string("agent"), "agent": .string(agent), "previous": optional(previous)]
        case let .modelChanged(model, previous):
            return [
                "notice": .string("model"),
                "model": model.cacheFields,
                "previous": previous?.cacheFields ?? .null,
            ]
        case let .instructionsUpdated(sources):
            return ["notice": .string("instructions"), "sources": .array(sources.map(AnyCodable.string))]
        case let .systemNote(description):
            return ["notice": .string("system"), "description": .string(description)]
        case .restart:
            return ["notice": .string("restart")]
        }
    }

    init?(cacheFields fields: [String: AnyCodable]) {
        func string(_ key: String) -> String? {
            if case .string(let value)? = fields[key] { return value }
            return nil
        }
        switch string("notice") {
        case "moved":
            guard let directory = string("directory") else { return nil }
            self = .moved(directory: directory)
        case "agent":
            guard let agent = string("agent") else { return nil }
            self = .agentChanged(agent: agent, previous: string("previous"))
        case "model":
            guard let model = fields["model"].flatMap(TimelineNoticeModel.init(cacheField:)) else { return nil }
            self = .modelChanged(model: model, previous: fields["previous"].flatMap(TimelineNoticeModel.init(cacheField:)))
        case "instructions":
            guard case .array(let values)? = fields["sources"] else { return nil }
            self = .instructionsUpdated(sources: values.compactMap { value in
                if case .string(let source) = value { return source }
                return nil
            })
        case "system":
            guard let description = string("description") else { return nil }
            self = .systemNote(description)
        case "restart":
            self = .restart
        default:
            return nil
        }
    }
}

private extension TimelineNoticeModel {
    var cacheFields: AnyCodable {
        .dictionary([
            "providerID": providerID.map(AnyCodable.string) ?? .null,
            "modelID": .string(modelID),
            "variant": variant.map(AnyCodable.string) ?? .null,
        ])
    }

    init?(cacheField: AnyCodable) {
        guard case .dictionary(let fields) = cacheField, case .string(let modelID)? = fields["modelID"] else {
            return nil
        }
        func string(_ key: String) -> String? {
            if case .string(let value)? = fields[key] { return value }
            return nil
        }
        self.init(providerID: string("providerID"), modelID: modelID, variant: string("variant"))
    }
}

private extension CachedMessagePartPayload {
    func toDomain() -> MessagePart? {
        switch kind {
        case "text":
            return .text(
                TextPart(
                    id: id,
                    text: text ?? "",
                    isStreaming: isStreaming,
                    isSynthetic: isSynthetic,
                    isIgnored: isIgnored
                )
            )
        case "tool":
            return .tool(
                ToolPart(
                    id: id,
                    toolName: toolName ?? "",
                    status: ToolStatus(rawValue: (status ?? "").lowercased()) ?? .pending,
                    title: title,
                    error: error,
                    callID: callId,
                    input: input,
                    output: output,
                    metadata: metadata,
                    timeStartEpochMs: timeStartEpochMs,
                    isInterrupted: isInterrupted,
                    isMalformed: isMalformed
                )
            )
        case "file":
            guard let fileId = id else { return nil }
            return .file(
                FilePart(
                    id: fileId,
                    filename: filename ?? "",
                    mime: mime ?? "",
                    kind: AttachmentKind(rawValue: (attachmentKind ?? "").lowercased()) ?? .text,
                    dataURL: dataUrl ?? "",
                    serverPath: serverPath
                )
            )
        case "reasoning":
            return .reasoning(
                ReasoningPart(
                    id: id,
                    text: text ?? "",
                    isStreaming: isStreaming,
                    timeStartEpochMs: timeStartEpochMs,
                    timeEndEpochMs: timeEndEpochMs
                )
            )
        case "compaction":
            return .compaction(
                CompactionPart(
                    id: id,
                    auto: auto ?? false,
                    completed: status == "completed",
                    summary: text.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
                )
            )
        case "notice":
            guard let id, let kind = metadata.flatMap(TimelineNoticeKind.init(cacheFields:)) else { return nil }
            return .notice(TimelineNotice(id: id, kind: kind))
        default:
            return nil
        }
    }
}
