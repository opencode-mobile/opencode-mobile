import Foundation

nonisolated struct V2SessionMessageDTO: Decodable, Sendable {
    let id: String
    let type: String
    let text: String?
    let files: [V2FileAttachmentDTO]
    let agents: [V2AgentAttachmentDTO]
    let agent: String?
    let model: V2ModelRefDTO?
    let content: [V2AssistantContentDTO]
    let cost: Double?
    let tokens: MessageTokensDTO?
    let error: V2UnknownErrorDTO?
    let time: V2MessageTimeDTO?
    let reason: String?
    let summary: String?
    let status: String?
    let command: String?
    let output: String?
    let callID: String?
    let name: String?
    let description: String?

    private enum CodingKeys: String, CodingKey {
        case id, type, text, files, agents, agent, model, content, cost, tokens, error, time
        case reason, summary, status, command, output, callID, name, description
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        type = try container.decode(String.self, forKey: .type)
        text = try container.decodeIfPresent(String.self, forKey: .text)
        files = try container.decodeIfPresent([V2FileAttachmentDTO].self, forKey: .files) ?? []
        agents = try container.decodeIfPresent([V2AgentAttachmentDTO].self, forKey: .agents) ?? []
        agent = try container.decodeIfPresent(String.self, forKey: .agent)
        model = try container.decodeIfPresent(V2ModelRefDTO.self, forKey: .model)
        content = try container.decodeIfPresent([V2AssistantContentDTO].self, forKey: .content) ?? []
        cost = try container.decodeIfPresent(Double.self, forKey: .cost)
        tokens = try container.decodeIfPresent(MessageTokensDTO.self, forKey: .tokens)
        error = try container.decodeIfPresent(V2UnknownErrorDTO.self, forKey: .error)
        time = try container.decodeIfPresent(V2MessageTimeDTO.self, forKey: .time)
        reason = try container.decodeIfPresent(String.self, forKey: .reason)
        summary = try container.decodeIfPresent(String.self, forKey: .summary)
        status = try container.decodeIfPresent(String.self, forKey: .status)
        command = try container.decodeIfPresent(String.self, forKey: .command)
        output = try container.decodeIfPresent(String.self, forKey: .output)
        callID = try container.decodeIfPresent(String.self, forKey: .callID)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        description = try container.decodeIfPresent(String.self, forKey: .description)
    }
}

nonisolated struct V2MessageTimeDTO: Decodable, Sendable {
    let created: Int64?
    let completed: Int64?
}

nonisolated struct V2ModelRefDTO: Decodable, Sendable {
    let id: String?
    let providerID: String?
    let variant: String?
}

nonisolated struct V2UnknownErrorDTO: Decodable, Sendable {
    let type: String?
    let message: String?
}

nonisolated struct V2FileAttachmentDTO: Decodable, Sendable {
    let uri: String?
    let mime: String?
    let name: String?
    let description: String?
    let source: V2PromptSourceDTO?
    let mention: V2PromptSourceDTO?
}

nonisolated struct V2AgentAttachmentDTO: Decodable, Sendable {
    let name: String?
    let source: V2PromptSourceDTO?
}

nonisolated struct V2PromptSourceDTO: Codable, Sendable {
    let start: Int?
    let end: Int?
    let text: String?

    init(start: Int?, end: Int?, text: String?) {
        self.start = start
        self.end = end
        self.text = text
    }
}

nonisolated struct V2AssistantContentDTO: Decodable, Sendable {
    let type: String
    let id: String?
    let text: String?
    let name: String?
    let state: V2ToolStateDTO?
    let time: V2MessageTimeDTO?
}

nonisolated struct V2ToolStateDTO: Decodable, Sendable {
    let status: String?
    let input: AnyCodable?
    let content: [V2ToolContentDTO]
    let error: V2UnknownErrorDTO?
    let structured: [String: AnyCodable]?
    let metadata: [String: AnyCodable]?

    private enum CodingKeys: String, CodingKey {
        case status, input, content, error, structured, metadata
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        status = try container.decodeIfPresent(String.self, forKey: .status)
        input = try container.decodeIfPresent(AnyCodable.self, forKey: .input)
        content = try container.decodeIfPresent([V2ToolContentDTO].self, forKey: .content) ?? []
        error = try container.decodeIfPresent(V2UnknownErrorDTO.self, forKey: .error)
        structured = try container.decodeIfPresent([String: AnyCodable].self, forKey: .structured)
        metadata = try container.decodeIfPresent([String: AnyCodable].self, forKey: .metadata)
    }
}

nonisolated struct V2ToolContentDTO: Decodable, Sendable {
    let type: String
    let text: String?
    let uri: String?
    let mime: String?
    let name: String?
}
