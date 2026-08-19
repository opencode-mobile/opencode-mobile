import Foundation

nonisolated struct MessageDomainAdapter: Sendable {
    func message(from dto: MessageDTO) -> Message {
        let info = dto.info
        let domainParts = parseParts(from: dto.parts)
        let flatText = displayText(from: domainParts)
        return Message(
            id: info.id,
            role: role(from: info.role),
            text: flatText.isEmpty ? errorText(from: info.error) : flatText,
            parts: domainParts,
            cost: info.cost,
            tokens: tokenUsage(from: info.tokens),
            model: info.model?.modelID ?? info.modelID,
            modelProviderID: info.model?.providerID ?? info.providerID,
            modelID: info.model?.modelID ?? info.modelID,
            variant: info.variant,
            mode: info.mode ?? info.agent,
            sentAtEpochMs: info.time?.created,
            contextInputTokens: info.tokens?.input,
            contextEstimate: contextEstimate(role: info.role, parts: dto.parts)
        )
    }

    func message(
        info: MessageInfoDTO,
        domainParts: [MessagePart]
    ) -> Message {
        let flatText = displayText(from: domainParts)
        return Message(
            id: info.id,
            role: role(from: info.role),
            text: flatText.isEmpty ? errorText(from: info.error) : flatText,
            parts: domainParts,
            cost: info.cost,
            tokens: tokenUsage(from: info.tokens),
            model: info.model?.modelID ?? info.modelID,
            modelProviderID: info.model?.providerID ?? info.providerID,
            modelID: info.model?.modelID ?? info.modelID,
            variant: info.variant,
            mode: info.mode ?? info.agent,
            sentAtEpochMs: info.time?.created,
            contextInputTokens: info.tokens?.input,
            contextEstimate: MessageContextEstimate()
        )
    }

    func messagePart(from dto: MessagePartDTO) -> MessagePart? {
        switch dto.type {
        case "text":
            guard let text = dto.text else { return nil }
            return .text(
                TextPart(
                    id: dto.id,
                    text: text,
                    isStreaming: false,
                    isSynthetic: dto.synthetic == true,
                    isIgnored: dto.ignored == true
                )
            )
        case "file":
            return filePart(from: dto)
        case "tool":
            guard let toolName = dto.tool ?? dto.name else { return nil }
            return .tool(toolPart(id: dto.id, toolName: toolName, callID: dto.callID, state: dto.state))
        case "compaction":
            return .compaction(CompactionPart(id: dto.id, auto: dto.auto == true, completed: dto.completed == true))
        case "reasoning":
            return .reasoning(
                ReasoningPart(
                    id: dto.id,
                    text: dto.text ?? "",
                    isStreaming: false,
                    timeStartEpochMs: dto.time?.start,
                    timeEndEpochMs: dto.time?.end
                )
            )
        default:
            return nil
        }
    }

    func toolPart(
        id: String? = nil,
        toolName: String,
        callID: String? = nil,
        state: MessageToolStateDTO?
    ) -> ToolPart {
        ToolPart(
            id: id,
            toolName: toolName,
            status: toolStatus(from: state?.status),
            title: state?.title.flatMap { $0.isEmpty ? nil : $0 },
            error: state?.error.flatMap { $0.isEmpty ? nil : $0 },
            callID: callID,
            input: state?.input,
            output: state?.output,
            metadata: state?.metadata,
            timeStartEpochMs: state?.time?.start
        )
    }

    func toolStatus(from raw: String?) -> ToolStatus {
        switch raw {
        case "pending", "streaming":
            .pending
        case "running":
            .running
        case "completed":
            .completed
        case "error":
            .error
        default:
            .pending
        }
    }

    func compactToolLabel(for tool: ToolPart) -> String {
        switch tool.status {
        case .pending:
            return "⌛ \(tool.toolName)"
        case .running:
            if let title = tool.title, !title.isEmpty {
                return "⏳ \(title)"
            }
            return "⏳ \(tool.toolName)"
        case .completed:
            return "✓ \(tool.toolName)"
        case .error:
            let message = tool.error.flatMap { $0.isEmpty ? nil : $0 } ?? "failed"
            return "✕ \(tool.toolName): \(message)"
        }
    }

    func flattenedText(from parts: [MessagePart]) -> String {
        displayText(from: parts)
    }

    func sendRequest(text: String) -> SendMessageRequestDTO {
        SendMessageRequestDTO(parts: [SendMessagePartDTO(type: "text", text: text)])
    }

    func sendRequest(options: PromptSendOptions) -> SendMessageRequestDTO {
        var parts: [SendMessagePartDTO] = []
        if !options.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            parts.append(SendMessagePartDTO(type: "text", text: options.text))
        }
        parts.append(contentsOf: ComposerMentionRouting.agentParts(text: options.text, mentions: options.agentMentions).map { part in
            SendMessagePartDTO(
                type: "agent",
                name: part.name,
                source: AgentPartSourceDTO(value: part.sourceValue, start: part.start, end: part.end)
            )
        })
        // Omit wire `id`: FilePartInput allows it only when it matches ^prt.
        // Local Attachment ids are UUIDs for composer chip identity.
        parts.append(contentsOf: options.attachments.map {
            SendMessagePartDTO(type: "file", mime: $0.mime, filename: $0.filename, url: $0.dataURL)
        })
        return SendMessageRequestDTO(
            parts: parts,
            model: options.model.map { SendPromptModelDTO(providerID: $0.providerID, modelID: $0.modelID) },
            variant: options.variant,
            agent: options.mode
        )
    }

    func v2PromptRequest(options: PromptSendOptions) -> V2PromptRequestDTO {
        let files = options.attachments.map {
            V2PromptFileDTO(uri: $0.dataURL, name: $0.filename, mime: $0.mime)
        }
        let agents = ComposerMentionRouting.agentParts(text: options.text, mentions: options.agentMentions).map { part in
            V2PromptAgentDTO(
                name: part.name,
                mention: V2PromptSourceDTO(start: part.start, end: part.end, text: part.sourceValue)
            )
        }
        return V2PromptRequestDTO(
            text: options.text,
            files: files.isEmpty ? nil : files,
            agents: agents.isEmpty ? nil : agents
        )
    }

    func v2SwitchModelRequest(options: PromptSendOptions) -> V2SwitchModelRequestDTO? {
        guard let model = options.model else { return nil }
        return V2SwitchModelRequestDTO(
            model: V2PromptModelRefDTO(id: model.modelID, providerID: model.providerID, variant: options.variant)
        )
    }

    func mapError(_ error: Error) -> MessageError {
        if let messageError = error as? MessageError {
            return messageError
        }
        if let apiError = error as? MessagesAPIError {
            switch apiError {
            case .invalidResponse, .noActiveConnection:
                return .transport("Invalid response")
            case let .httpStatus(code, message):
                return .http(statusCode: code, message: message)
            }
        }
        if error is DecodingError {
            return .decoding(error.localizedDescription)
        }
        return .transport(error.localizedDescription)
    }

    // MARK: Part parsing

    func parseParts(from dtos: [MessagePartDTO]) -> [MessagePart] {
        dtos.compactMap { messagePart(from: $0) }
    }

    func messages(fromV2 dtos: [V2SessionMessageDTO]) -> [Message] {
        var messages: [Message] = []
        var agent = ""
        var model: V2ModelRefDTO?
        for dto in dtos {
            switch dto.type {
            case "agent-switched", "location-switched":
                agent = dto.agent ?? agent
            case "model-switched":
                model = dto.model
            case "user":
                messages.append(projectV2User(dto, agent: agent, model: model))
                agent = dto.agent ?? agent
                model = dto.model ?? model
            case "synthetic":
                if let synthetic = projectV2Synthetic(dto, agent: agent, model: model) {
                    messages.append(synthetic)
                }
            case "skill":
                messages.append(projectV2Skill(dto, agent: agent, model: model))
            case "shell":
                messages.append(contentsOf: projectV2Shell(dto, agent: agent, model: model))
            case "assistant":
                agent = dto.agent ?? agent
                model = dto.model ?? model
                messages.append(projectV2Assistant(dto, agent: agent, model: model))
            case "compaction":
                messages.append(projectV2Compaction(dto))
            default:
                break
            }
        }
        return messages
    }

    // MARK: Private

    private func projectV2User(_ dto: V2SessionMessageDTO, agent: String, model: V2ModelRefDTO?) -> Message {
        var parts: [MessagePart] = []
        let text = dto.text ?? ""
        if !text.isEmpty {
            parts.append(.text(TextPart(id: "\(dto.id):text:0", text: text, isStreaming: false)))
        }
        for (index, file) in dto.files.enumerated() {
            if let part = v2FilePart(file, fallbackID: "\(dto.id):file:\(index)") {
                parts.append(part)
            }
        }
        return messageFromV2(
            id: dto.id,
            role: .user,
            parts: parts,
            mode: dto.agent ?? (agent.isEmpty ? nil : agent),
            model: dto.model ?? model,
            sentAtEpochMs: dto.time?.created,
            contextEstimate: MessageContextEstimate(
                userCharacters: text.count
                    + dto.files.reduce(0) { $0 + ($1.mention?.text?.count ?? $1.source?.text?.count ?? 0) }
                    + dto.agents.reduce(0) { $0 + ($1.mention?.text?.count ?? $1.source?.text?.count ?? 0) }
            )
        )
    }

    private func projectV2Synthetic(_ dto: V2SessionMessageDTO, agent: String, model: V2ModelRefDTO?) -> Message? {
        let text = dto.description?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? dto.text?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        guard let text else { return nil }
        return messageFromV2(
            id: dto.id,
            role: .user,
            parts: [.text(TextPart(id: "\(dto.id):text:0", text: text, isStreaming: false, isSynthetic: true))],
            mode: agent.isEmpty ? nil : agent,
            model: model,
            sentAtEpochMs: dto.time?.created,
            contextEstimate: MessageContextEstimate(userCharacters: text.count)
        )
    }

    private func projectV2Skill(_ dto: V2SessionMessageDTO, agent: String, model: V2ModelRefDTO?) -> Message {
        let text = dto.text ?? ""
        return messageFromV2(
            id: dto.id,
            role: .user,
            parts: text.isEmpty ? [] : [.text(TextPart(id: "\(dto.id):text:0", text: text, isStreaming: false))],
            mode: dto.name ?? (agent.isEmpty ? nil : agent),
            model: model,
            sentAtEpochMs: dto.time?.created,
            contextEstimate: MessageContextEstimate(userCharacters: text.count)
        )
    }

    private func projectV2Shell(_ dto: V2SessionMessageDTO, agent: String, model: V2ModelRefDTO?) -> [Message] {
        let command = dto.command ?? ""
        let completed = dto.time?.completed != nil
        let user = messageFromV2(
            id: dto.id,
            role: .user,
            parts: command.isEmpty ? [] : [.text(TextPart(id: "\(dto.id):text:0", text: command, isStreaming: false))],
            mode: agent.isEmpty ? nil : agent,
            model: model,
            sentAtEpochMs: dto.time?.created,
            contextEstimate: MessageContextEstimate(userCharacters: command.count)
        )
        let assistant = messageFromV2(
            id: "\(dto.id):assistant",
            role: .assistant,
            parts: [
                .tool(
                    ToolPart(
                        id: "\(dto.id):tool",
                        toolName: "bash",
                        status: completed ? .completed : .running,
                        title: "Shell",
                        callID: dto.callID,
                        output: dto.output
                    )
                )
            ],
            mode: agent.isEmpty ? nil : agent,
            model: model,
            sentAtEpochMs: dto.time?.created
        )
        return [user, assistant]
    }

    private func projectV2Assistant(_ dto: V2SessionMessageDTO, agent: String, model: V2ModelRefDTO?) -> Message {
        let parts = dto.content.enumerated().compactMap { index, content in
            v2AssistantPart(content, fallbackID: "\(dto.id):\(content.type):\(index)")
        }
        return messageFromV2(
            id: dto.id,
            role: .assistant,
            parts: parts,
            fallbackText: dto.error?.message?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "",
            cost: dto.cost,
            tokens: tokenUsage(from: dto.tokens),
            mode: dto.agent ?? (agent.isEmpty ? nil : agent),
            model: dto.model ?? model,
            sentAtEpochMs: dto.time?.created,
            contextInputTokens: dto.tokens?.input,
            contextEstimate: v2AssistantContextEstimate(dto.content)
        )
    }

    private func projectV2Compaction(_ dto: V2SessionMessageDTO) -> Message {
        let summary = dto.summary ?? ""
        var parts: [MessagePart] = [
            .compaction(
                CompactionPart(
                    id: dto.id,
                    auto: dto.reason == "auto",
                    completed: dto.status?.caseInsensitiveCompare("completed") == .orderedSame
                )
            )
        ]
        if !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            parts.append(.text(TextPart(id: "\(dto.id):text:0", text: summary, isStreaming: false)))
        }
        return messageFromV2(
            id: dto.id,
            role: .assistant,
            parts: parts,
            mode: "compaction",
            sentAtEpochMs: dto.time?.created,
            contextEstimate: MessageContextEstimate(assistantCharacters: summary.count)
        )
    }

    private func messageFromV2(
        id: String,
        role: MessageRole,
        parts: [MessagePart],
        fallbackText: String = "",
        cost: Double? = nil,
        tokens: MessageTokenUsage? = nil,
        mode: String? = nil,
        model: V2ModelRefDTO? = nil,
        sentAtEpochMs: Int64? = nil,
        contextInputTokens: Int64? = nil,
        contextEstimate: MessageContextEstimate = MessageContextEstimate()
    ) -> Message {
        let flatText = displayText(from: parts)
        return Message(
            id: id,
            role: role,
            text: flatText.isEmpty ? fallbackText : flatText,
            parts: parts,
            cost: cost,
            tokens: tokens,
            model: model?.id,
            modelProviderID: model?.providerID,
            modelID: model?.id,
            variant: model?.variant,
            mode: mode,
            sentAtEpochMs: sentAtEpochMs,
            contextInputTokens: contextInputTokens,
            contextEstimate: contextEstimate
        )
    }

    private func v2AssistantPart(_ content: V2AssistantContentDTO, fallbackID: String) -> MessagePart? {
        switch content.type {
        case "text":
            guard let text = content.text else { return nil }
            return .text(TextPart(id: content.id ?? fallbackID, text: text, isStreaming: false))
        case "reasoning", "thinking":
            return .reasoning(
                ReasoningPart(
                    id: content.id ?? fallbackID,
                    text: content.text ?? "",
                    isStreaming: false,
                    timeStartEpochMs: content.time?.created,
                    timeEndEpochMs: content.time?.completed
                )
            )
        case "tool":
            guard let name = content.name else { return nil }
            let state = content.state
            return .tool(
                ToolPart(
                    id: content.id ?? fallbackID,
                    toolName: name,
                    status: toolStatus(from: state?.status),
                    error: state?.error?.message.flatMap { $0.isEmpty ? nil : $0 },
                    callID: content.id,
                    input: dictionaryFromInput(state?.input),
                    output: toolOutput(state),
                    metadata: state?.metadata ?? state?.structured,
                    timeStartEpochMs: content.time?.created
                )
            )
        default:
            return nil
        }
    }

    private func filePart(from dto: MessagePartDTO) -> MessagePart? {
        let url = dto.url?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return resolvedFilePart(
            id: dto.id ?? (url.isEmpty ? nil : url) ?? dto.filename ?? "file",
            filename: dto.filename,
            mime: dto.mime,
            dataURL: url
        )
    }

    private func v2FilePart(_ file: V2FileAttachmentDTO, fallbackID: String) -> MessagePart? {
        resolvedFilePart(
            id: fallbackID,
            filename: file.name,
            mime: file.mime,
            dataURL: v2FileDataURL(file) ?? ""
        )
    }

    private func resolvedFilePart(
        id: String,
        filename: String?,
        mime: String?,
        dataURL: String
    ) -> MessagePart? {
        let resolvedMime = (
            mime
                ?? AttachmentDataURL.mime(fromDataURL: dataURL)
                ?? filename.flatMap(AttachmentDataURL.mime(fromFilename:))
        )?.lowercased()
        guard let resolvedMime, let kind = AttachmentDataURL.kind(for: resolvedMime) else { return nil }
        if !dataURL.isEmpty {
            guard dataURL.lowercased().hasPrefix("data:"),
                  AttachmentDataURL.validates(dataURL, mime: resolvedMime)
            else { return nil }
        }
        return .file(
            FilePart(
                id: id,
                filename: filename?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "Attachment",
                mime: resolvedMime,
                kind: kind,
                dataURL: dataURL
            )
        )
    }

    private func v2FileDataURL(_ file: V2FileAttachmentDTO) -> String? {
        if let uri = file.uri?.nilIfEmpty { return uri }
        if let uri = file.source?.uri?.nilIfEmpty { return uri }
        guard let mime = file.mime, let data = file.data, let decoded = Data(base64Encoded: data) else {
            return nil
        }
        return AttachmentDataURL.make(data: decoded, mime: mime)
    }

    private func dictionaryFromInput(_ input: AnyCodable?) -> [String: AnyCodable]? {
        switch input {
        case let .dictionary(object):
            return object
        case let .string(raw):
            guard let data = raw.data(using: .utf8),
                  let decoded = try? JSONDecoder().decode([String: AnyCodable].self, from: data)
            else { return nil }
            return decoded
        default:
            return nil
        }
    }

    private func toolOutput(_ state: V2ToolStateDTO?) -> String? {
        let texts = state?.content.compactMap { item -> String? in
            guard item.type == "text" else { return nil }
            return item.text.flatMap { $0.isEmpty ? nil : $0 }
        } ?? []
        let joined = texts.joined(separator: "\n")
        return joined.isEmpty ? nil : joined
    }

    private func v2AssistantContextEstimate(_ content: [V2AssistantContentDTO]) -> MessageContextEstimate {
        let assistantCharacters = content.reduce(0) { total, item in
            switch item.type {
            case "text", "reasoning":
                total + (item.text?.count ?? 0)
            default:
                total
            }
        }
        let toolCharacters = content.reduce(0) { total, item in
            guard item.type == "tool", let state = item.state else { return total }
            let inputOverhead = (dictionaryFromInput(state.input)?.keys.count ?? 0) * 16
            let payload: Int
            switch state.status {
            case "pending", "streaming":
                if case let .string(raw)? = state.input {
                    payload = raw.count
                } else {
                    payload = 0
                }
            case "completed":
                payload = toolOutput(state)?.count ?? 0
            case "error":
                payload = state.error?.message?.count ?? 0
            default:
                payload = 0
            }
            return total + inputOverhead + payload
        }
        return MessageContextEstimate(assistantCharacters: assistantCharacters, toolCharacters: toolCharacters)
    }

    private func role(from raw: String) -> MessageRole {
        switch raw.lowercased() {
        case "user":
            .user
        case "assistant":
            .assistant
        case "command":
            .command
        case "tool":
            .tool
        default:
            .unknown
        }
    }

    private func errorText(from error: MessageErrorInfoDTO?) -> String {
        guard let error else { return "" }
        if let message = error.data?.message?.trimmingCharacters(in: .whitespacesAndNewlines), !message.isEmpty {
            return message
        }
        if let body = error.data?.responseBody?.trimmingCharacters(in: .whitespacesAndNewlines), !body.isEmpty {
            return body
        }
        if let name = error.name?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty {
            return name
        }
        return ""
    }

    private func displayText(from parts: [MessagePart]) -> String {
        let segments: [String] = parts.compactMap { part in
            switch part {
            case .text(let t):
                guard t.isDisplayable else { return nil }
                let trimmed = t.text.trimmingCharacters(in: .whitespacesAndNewlines)
                return trimmed.isEmpty ? nil : trimmed
            case .file, .tool, .compaction, .reasoning:
                return nil
            }
        }
        return segments.joined(separator: "\n")
    }

    private func tokenUsage(from dto: MessageTokensDTO?) -> MessageTokenUsage? {
        guard let dto else { return nil }
        return MessageTokenUsage(
            input: dto.input ?? 0,
            output: dto.output ?? 0,
            reasoning: dto.reasoning ?? 0,
            cacheRead: dto.cache?.read ?? 0,
            cacheWrite: dto.cache?.write ?? 0
        )
    }

    private func contextEstimate(role: String, parts: [MessagePartDTO]) -> MessageContextEstimate {
        switch role.lowercased() {
        case "user":
            return MessageContextEstimate(
                systemCharacters: parts.reduce(0) { $0 + ($1.system?.count ?? 0) },
                userCharacters: parts.reduce(0) { $0 + userContextCharacters(from: $1) }
            )
        case "assistant":
            let assistantCharacters = parts.reduce(0) { total, part in
                switch part.type {
                case "text", "reasoning":
                    total + (part.text?.count ?? 0)
                default:
                    total
                }
            }
            let toolCharacters = parts.reduce(0) { $0 + toolContextCharacters(from: $1) }
            return MessageContextEstimate(assistantCharacters: assistantCharacters, toolCharacters: toolCharacters)
        default:
            return MessageContextEstimate()
        }
    }

    private func userContextCharacters(from part: MessagePartDTO) -> Int {
        switch part.type {
        case "text":
            return part.text?.count ?? 0
        case "file":
            if case let .dictionary(text)? = part.source?["text"],
               case let .string(value)? = text["value"] {
                return value.count
            }
            return 0
        case "agent":
            if case let .string(value)? = part.source?["value"] {
                return value.count
            }
            return 0
        default:
            return 0
        }
    }

    private func toolContextCharacters(from part: MessagePartDTO) -> Int {
        guard part.type == "tool", let state = part.state else { return 0 }
        let inputOverhead = (state.input?.keys.count ?? 0) * 16
        switch state.status {
        case "pending":
            return inputOverhead + (state.raw?.count ?? 0)
        case "completed":
            return inputOverhead + (state.output?.count ?? 0)
        case "error":
            return inputOverhead + (state.error?.count ?? 0)
        default:
            return inputOverhead
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
