import Foundation

struct MessageDomainAdapter: Sendable {
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
            return .text(TextPart(text: text, isStreaming: false))
        case "file":
            guard let url = dto.url,
                  let mime = dto.mime?.lowercased(),
                  url.lowercased().hasPrefix("data:"),
                  AttachmentDataURL.validates(url, mime: mime),
                  let kind = AttachmentDataURL.kind(for: mime)
            else { return nil }
            return .file(
                FilePart(
                    id: dto.id ?? url,
                    filename: dto.filename?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "Attachment",
                    mime: mime,
                    kind: kind,
                    dataURL: url
                )
            )
        case "tool":
            guard let toolName = dto.tool else { return nil }
            return .tool(toolPart(toolName: toolName, callID: dto.callID, state: dto.state))
        default:
            return nil
        }
    }

    func toolPart(
        toolName: String,
        callID: String? = nil,
        state: MessageToolStateDTO?
    ) -> ToolPart {
        ToolPart(
            toolName: toolName,
            status: toolStatus(from: state?.status),
            title: state?.title.flatMap { $0.isEmpty ? nil : $0 },
            error: state?.error.flatMap { $0.isEmpty ? nil : $0 },
            callID: callID,
            input: state?.input,
            output: state?.output,
            metadata: state?.metadata
        )
    }

    func toolStatus(from raw: String?) -> ToolStatus {
        switch raw {
        case "pending":
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

    // MARK: Private

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
                let trimmed = t.text.trimmingCharacters(in: .whitespacesAndNewlines)
                return trimmed.isEmpty ? nil : trimmed
            case .file:
                return nil
            case .tool:
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
