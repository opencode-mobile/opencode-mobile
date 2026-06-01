import Foundation

struct MessageDomainAdapter: Sendable {
    func message(from dto: MessageDTO) -> Message {
        let info = dto.info
        let partText = displayText(from: dto.parts)
        return Message(
            id: info.id,
            role: role(from: info.role),
            text: partText.isEmpty ? errorText(from: info.error) : partText,
            model: info.model?.modelID ?? info.modelID,
            modelProviderID: info.model?.providerID ?? info.providerID,
            modelID: info.model?.modelID ?? info.modelID,
            variant: info.variant,
            mode: info.mode ?? info.agent,
            sentAtEpochMs: info.time?.created,
            contextInputTokens: info.tokens?.input
        )
    }

    func sendRequest(text: String) -> SendMessageRequestDTO {
        SendMessageRequestDTO(parts: [SendMessagePartDTO(type: "text", text: text)])
    }

    func sendRequest(options: PromptSendOptions) -> SendMessageRequestDTO {
        SendMessageRequestDTO(
            parts: [SendMessagePartDTO(type: "text", text: options.text)],
            model: options.model.map { SendPromptModelDTO(providerID: $0.providerID, modelID: $0.modelID) },
            variant: options.variant
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

    private func displayText(from parts: [MessagePartDTO]) -> String {
        let textParts = parts
            .filter { $0.type == "text" }
            .compactMap(\.text)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        if !textParts.isEmpty {
            return textParts.joined(separator: "\n")
        }
        let toolLabels = parts
            .filter { $0.type == "tool" }
            .compactMap { part -> String? in
                guard let tool = part.tool else { return nil }
                return "Tool: \(tool)"
            }
        if !toolLabels.isEmpty {
            return toolLabels.joined(separator: "\n")
        }
        return ""
    }
}
