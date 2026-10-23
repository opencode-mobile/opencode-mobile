import Foundation

nonisolated struct V2SessionMessageDTO: Decodable, Sendable {
    let id: String
    let type: String
    let text: String?
    let files: [V2FileAttachmentDTO]
    let agents: [V2AgentAttachmentDTO]
    /// Skills admitted on a `user` prompt. The body is model-facing and not decoded.
    let skills: [V2UserSkillDTO]
    let agent: String?
    let model: V2ModelRefDTO?
    let content: [V2AssistantContentDTO]
    let cost: Double?
    let tokens: MessageTokensDTO?
    let error: V2UnknownErrorDTO?
    let outcome: String?
    let retry: V2RetryDTO?
    let finish: String?
    let time: V2MessageTimeDTO?
    let reason: String?
    let summary: String?
    let status: String?
    let command: String?
    let output: String?
    /// `shell` items: the command's exit code once it ended.
    let exit: Int?
    let callID: String?
    let name: String?
    let description: String?
    /// Notice items: the agent, model or location before a switch.
    let previous: V2PreviousDTO?
    /// `location-switched`: the session's new location.
    let location: V2LocationRefDTO?
    let metadata: [String: AnyCodable]?

    private enum CodingKeys: String, CodingKey {
        case id, type, text, files, agents, skills, agent, model, content, cost, tokens, error, outcome, retry, finish, time
        case reason, summary, status, command, output, exit, callID, name, description, previous, location, metadata
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        type = try container.decode(String.self, forKey: .type)
        text = try container.decodeIfPresent(String.self, forKey: .text)
        files = try container.decodeIfPresent([V2FileAttachmentDTO].self, forKey: .files) ?? []
        agents = try container.decodeIfPresent([V2AgentAttachmentDTO].self, forKey: .agents) ?? []
        skills = try container.decodeIfPresent([V2UserSkillDTO].self, forKey: .skills) ?? []
        agent = try container.decodeIfPresent(String.self, forKey: .agent)
        model = try container.decodeIfPresent(V2ModelRefDTO.self, forKey: .model)
        content = try container.decodeIfPresent([V2AssistantContentDTO].self, forKey: .content) ?? []
        cost = try container.decodeIfPresent(Double.self, forKey: .cost)
        tokens = try container.decodeIfPresent(MessageTokensDTO.self, forKey: .tokens)
        error = try container.decodeIfPresent(V2UnknownErrorDTO.self, forKey: .error)
        outcome = try container.decodeIfPresent(String.self, forKey: .outcome)
        retry = try container.decodeIfPresent(V2RetryDTO.self, forKey: .retry)
        finish = try container.decodeIfPresent(String.self, forKey: .finish)
        time = try container.decodeIfPresent(V2MessageTimeDTO.self, forKey: .time)
        reason = try container.decodeIfPresent(String.self, forKey: .reason)
        summary = try container.decodeIfPresent(String.self, forKey: .summary)
        status = try container.decodeIfPresent(String.self, forKey: .status)
        command = try container.decodeIfPresent(String.self, forKey: .command)
        output = Self.decodeOutput(from: container)
        exit = try? container.decodeIfPresent(Int.self, forKey: .exit)
        callID = try container.decodeIfPresent(String.self, forKey: .callID)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        description = try container.decodeIfPresent(String.self, forKey: .description)
        previous = try? container.decodeIfPresent(V2PreviousDTO.self, forKey: .previous)
        location = try? container.decodeIfPresent(V2LocationRefDTO.self, forKey: .location)
        metadata = try? container.decodeIfPresent([String: AnyCodable].self, forKey: .metadata)
    }

    /// The fields a timeline notice reads, shared with the live projection.
    var notice: V2NoticeDTO {
        V2NoticeDTO(
            type: type,
            agent: agent,
            model: model,
            previous: previous,
            location: location,
            metadata: metadata,
            description: description
        )
    }

    private static func decodeOutput(from container: KeyedDecodingContainer<CodingKeys>) -> String? {
        if let value = try? container.decode(String.self, forKey: .output) {
            return value
        }
        return try? container.decode(V2ShellOutputDTO.self, forKey: .output).output
    }
}

nonisolated struct V2ShellOutputDTO: Decodable, Sendable {
    let output: String?
}

nonisolated struct V2MessageTimeDTO: Decodable, Sendable {
    let created: Int64?
    let completed: Int64?
}

nonisolated struct V2ModelRefDTO: Codable, Sendable {
    let id: String?
    let providerID: String?
    let variant: String?
}

nonisolated struct V2LocationRefDTO: Codable, Sendable {
    let directory: String?
}

/// `previous` on a switch item: an agent id (`agent-switched`), a model ref
/// (`model-switched`) or `{location}` (`location-switched`).
nonisolated struct V2PreviousDTO: Codable, Sendable {
    let agent: String?
    let model: V2ModelRefDTO?
    let location: V2LocationRefDTO?

    init(agent: String? = nil, model: V2ModelRefDTO? = nil, location: V2LocationRefDTO? = nil) {
        self.agent = agent
        self.model = model
        self.location = location
    }

    private enum CodingKeys: String, CodingKey {
        case id, providerID, variant, location
    }

    init(from decoder: Decoder) throws {
        if let agent = try? decoder.singleValueContainer().decode(String.self) {
            self.init(agent: agent)
            return
        }
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let location = try? container.decodeIfPresent(V2LocationRefDTO.self, forKey: .location)
        let id = try? container.decodeIfPresent(String.self, forKey: .id)
        let model = id.map { id in
            V2ModelRefDTO(
                id: id,
                providerID: try? container.decodeIfPresent(String.self, forKey: .providerID),
                variant: try? container.decodeIfPresent(String.self, forKey: .variant)
            )
        }
        self.init(model: model, location: location)
    }

    func encode(to encoder: Encoder) throws {
        if let agent {
            var container = encoder.singleValueContainer()
            try container.encode(agent)
            return
        }
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(model?.id, forKey: .id)
        try container.encodeIfPresent(model?.providerID, forKey: .providerID)
        try container.encodeIfPresent(model?.variant, forKey: .variant)
        try container.encodeIfPresent(location, forKey: .location)
    }
}

/// The fields of a v2 notice item (`location-switched`, `agent-switched`,
/// `model-switched`, `system`, `synthetic`). REST reads them off the session
/// message; the live projection carries them on a client-only `notice` part.
nonisolated struct V2NoticeDTO: Codable, Sendable {
    let type: String
    let agent: String?
    let model: V2ModelRefDTO?
    let previous: V2PreviousDTO?
    let location: V2LocationRefDTO?
    let metadata: [String: AnyCodable]?
    let description: String?

    init(
        type: String,
        agent: String? = nil,
        model: V2ModelRefDTO? = nil,
        previous: V2PreviousDTO? = nil,
        location: V2LocationRefDTO? = nil,
        metadata: [String: AnyCodable]? = nil,
        description: String? = nil
    ) {
        self.type = type
        self.agent = agent
        self.model = model
        self.previous = previous
        self.location = location
        self.metadata = metadata
        self.description = description
    }
}

nonisolated struct V2UnknownErrorDTO: Decodable, Sendable {
    let type: String?
    let message: String?
    let status: Int?
    let response: V2ErrorResponseDTO?

    init(type: String? = nil, message: String? = nil, status: Int? = nil, response: V2ErrorResponseDTO? = nil) {
        self.type = type
        self.message = message
        self.status = status
        self.response = response
    }
}

nonisolated struct V2ErrorResponseDTO: Decodable, Sendable {
    let body: String?
}

/// In-progress retry on an assistant message. The working label reads the live event, not this field.
nonisolated struct V2RetryDTO: Decodable, Sendable {
    let attempt: Int?
    let at: Int64?
    let error: V2UnknownErrorDTO?
}

nonisolated struct V2FileAttachmentDTO: Decodable, Sendable {
    let uri: String?
    let mime: String?
    let name: String?
    let description: String?
    let data: String?
    let source: V2FileSourceDTO?
    let mention: V2PromptSourceDTO?

    private enum CodingKeys: String, CodingKey {
        case uri, mime, name, description, data, source, mention
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        uri = try container.decodeIfPresent(String.self, forKey: .uri)
        mime = try container.decodeIfPresent(String.self, forKey: .mime)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        description = try container.decodeIfPresent(String.self, forKey: .description)
        data = try? container.decode(String.self, forKey: .data)
        source = try container.decodeIfPresent(V2FileSourceDTO.self, forKey: .source)
        mention = try container.decodeIfPresent(V2PromptSourceDTO.self, forKey: .mention)
            ?? source.flatMap { source in
                guard source.start != nil || source.end != nil || source.text != nil else { return nil }
                return V2PromptSourceDTO(start: source.start, end: source.end, text: source.text)
            }
    }
}

nonisolated struct V2FileSourceDTO: Decodable, Sendable {
    let type: String?
    let uri: String?
    let start: Int?
    let end: Int?
    let text: String?
}

nonisolated struct V2AgentAttachmentDTO: Decodable, Sendable {
    let name: String?
    let source: V2PromptSourceDTO?
    let mention: V2PromptSourceDTO?

    private enum CodingKeys: String, CodingKey {
        case name, source, mention
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        source = try container.decodeIfPresent(V2PromptSourceDTO.self, forKey: .source)
        mention = try container.decodeIfPresent(V2PromptSourceDTO.self, forKey: .mention)
            ?? source
    }
}

nonisolated struct V2UserSkillDTO: Decodable, Sendable {
    let id: String?
    let name: String?
    let mention: V2PromptSourceDTO?
}

nonisolated struct V2PromptSourceDTO: Codable, Sendable, Equatable {
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
    let executed: Bool?
    let structured: [String: AnyCodable]?
    let metadata: [String: AnyCodable]?

    private enum CodingKeys: String, CodingKey {
        case status, input, content, error, executed, structured, metadata
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        status = try container.decodeIfPresent(String.self, forKey: .status)
        input = try container.decodeIfPresent(AnyCodable.self, forKey: .input)
        content = try container.decodeIfPresent([V2ToolContentDTO].self, forKey: .content) ?? []
        error = try container.decodeIfPresent(V2UnknownErrorDTO.self, forKey: .error)
        executed = try container.decodeIfPresent(Bool.self, forKey: .executed)
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
