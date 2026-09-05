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
            messages: nil
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
            messages: CachedMessageListPayload(messages: messages.map { $0.toCachePayload() })
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
}

nonisolated enum SessionCacheCodecError: Error {
    case utf8EncodingFailed
}

// MARK: - Envelope

nonisolated private struct CachedEnvelope: Codable, Sendable {
    let schemaVersion: Int
    let sessions: CachedSessionListPayload?
    let messages: CachedMessageListPayload?
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
}

nonisolated private struct CachedSessionRevertPayload: Codable, Sendable {
    let messageId: String
    let partId: String?
    let snapshot: String?
    let diff: String?
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
    let contextInputTokens: Int64?
    let contextEstimate: CachedMessageContextEstimatePayload

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
        contextInputTokens: Int64? = nil,
        contextEstimate: CachedMessageContextEstimatePayload = CachedMessageContextEstimatePayload()
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
        self.contextInputTokens = contextInputTokens
        self.contextEstimate = contextEstimate
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
        contextInputTokens = try container.decodeIfPresent(Int64.self, forKey: .contextInputTokens)
        contextEstimate = try container.decodeIfPresent(
            CachedMessageContextEstimatePayload.self,
            forKey: .contextEstimate
        ) ?? CachedMessageContextEstimatePayload()
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
    let timeStartEpochMs: Int64?
    let timeEndEpochMs: Int64?

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
        timeStartEpochMs: Int64? = nil,
        timeEndEpochMs: Int64? = nil
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
        self.timeStartEpochMs = timeStartEpochMs
        self.timeEndEpochMs = timeEndEpochMs
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
        timeStartEpochMs = try container.decodeIfPresent(Int64.self, forKey: .timeStartEpochMs)
        timeEndEpochMs = try container.decodeIfPresent(Int64.self, forKey: .timeEndEpochMs)
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
            revert: revert?.toCachePayload()
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
            revert: revert?.toDomain()
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
            contextInputTokens: contextInputTokens,
            contextEstimate: contextEstimate.toCachePayload()
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
            contextInputTokens: contextInputTokens,
            contextEstimate: contextEstimate.toDomain()
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
                timeStartEpochMs: part.timeStartEpochMs
            )
        case let .file(part):
            return CachedMessagePartPayload(
                kind: "file",
                id: part.id,
                filename: part.filename,
                mime: part.mime,
                attachmentKind: part.kind.rawValue,
                dataUrl: AttachmentLimits.persistedDataURL(part.dataURL)
            )
        case .compaction:
            // Live marker only; rehydrated from SSE / refresh while session is open.
            return nil
        case let .reasoning(part):
            return CachedMessagePartPayload(
                kind: "reasoning",
                id: part.id,
                text: part.text,
                isStreaming: part.isStreaming,
                timeStartEpochMs: part.timeStartEpochMs,
                timeEndEpochMs: part.timeEndEpochMs
            )
        }
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
                    timeStartEpochMs: timeStartEpochMs
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
                    dataURL: dataUrl ?? ""
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
        default:
            return nil
        }
    }
}
