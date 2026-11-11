import Foundation

public nonisolated struct V2SessionMessageDTO: Decodable, Sendable {
    public let id: String
    public let type: String
    public let text: String?
    public let files: [V2FileAttachmentDTO]
    public let agents: [V2AgentAttachmentDTO]
    /// Skills admitted on a `user` prompt. The body is model-facing and not decoded.
    public let skills: [V2UserSkillDTO]
    public let agent: String?
    public let model: V2ModelRefDTO?
    public let content: [V2AssistantContentDTO]
    public let cost: Double?
    public let tokens: MessageTokensDTO?
    public let error: V2UnknownErrorDTO?
    public let outcome: String?
    public let retry: V2RetryDTO?
    public let finish: String?
    public let time: V2MessageTimeDTO?
    public let reason: String?
    public let summary: String?
    public let status: String?
    public let command: String?
    public let output: String?
    /// `shell` items: the command's exit code once it ended.
    public let exit: Int?
    public let callID: String?
    public let name: String?
    public let description: String?
    /// Notice items: the agent, model or location before a switch.
    public let previous: V2PreviousDTO?
    /// `location-switched`: the session's new location.
    public let location: V2LocationRefDTO?
    public let metadata: [String: AnyCodable]?

    private enum CodingKeys: String, CodingKey {
        case id, type, text, files, agents, skills, agent, model, content, cost, tokens, error, outcome, retry, finish, time
        case reason, summary, status, command, output, exit, callID, name, description, previous, location, metadata
    }

    public init(from decoder: Decoder) throws {
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
    public var notice: V2NoticeDTO {
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

public nonisolated struct V2ShellOutputDTO: Decodable, Sendable {
    public let output: String?

    public init(output: String?) {
        self.output = output
    }
}

public nonisolated struct V2MessageTimeDTO: Decodable, Sendable {
    public let created: Int64?
    public let completed: Int64?

    public init(
        created: Int64?,
        completed: Int64?
    ) {
        self.created = created
        self.completed = completed
    }
}

public nonisolated struct V2ModelRefDTO: Codable, Sendable {
    public let id: String?
    public let providerID: String?
    public let variant: String?

    public init(
        id: String?,
        providerID: String?,
        variant: String?
    ) {
        self.id = id
        self.providerID = providerID
        self.variant = variant
    }
}

public nonisolated struct V2LocationRefDTO: Codable, Sendable {
    public let directory: String?

    public init(directory: String?) {
        self.directory = directory
    }
}

/// `previous` on a switch item: an agent id (`agent-switched`), a model ref
/// (`model-switched`) or `{location}` (`location-switched`).
public nonisolated struct V2PreviousDTO: Codable, Sendable {
    public let agent: String?
    public let model: V2ModelRefDTO?
    public let location: V2LocationRefDTO?

    public init(agent: String? = nil, model: V2ModelRefDTO? = nil, location: V2LocationRefDTO? = nil) {
        self.agent = agent
        self.model = model
        self.location = location
    }

    private enum CodingKeys: String, CodingKey {
        case id, providerID, variant, location
    }

    public init(from decoder: Decoder) throws {
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

    public func encode(to encoder: Encoder) throws {
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
public nonisolated struct V2NoticeDTO: Codable, Sendable {
    public let type: String
    public let agent: String?
    public let model: V2ModelRefDTO?
    public let previous: V2PreviousDTO?
    public let location: V2LocationRefDTO?
    public let metadata: [String: AnyCodable]?
    public let description: String?

    public init(
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

public nonisolated struct V2UnknownErrorDTO: Decodable, Sendable {
    public let type: String?
    public let message: String?
    public let status: Int?
    public let response: V2ErrorResponseDTO?
    /// How long the provider asked to wait, counted from when the failure was recorded.
    public let retryAfterMs: Int64?

    public init(
        type: String? = nil,
        message: String? = nil,
        status: Int? = nil,
        response: V2ErrorResponseDTO? = nil,
        retryAfterMs: Int64? = nil
    ) {
        self.type = type
        self.message = message
        self.status = status
        self.response = response
        self.retryAfterMs = retryAfterMs
    }
}

public nonisolated struct V2ErrorResponseDTO: Decodable, Sendable {
    public let body: String?

    public init(body: String?) {
        self.body = body
    }
}

/// In-progress retry on an assistant message. The working label reads the live event, not this field.
public nonisolated struct V2RetryDTO: Decodable, Sendable {
    public let attempt: Int?
    public let at: Int64?
    public let error: V2UnknownErrorDTO?

    public init(
        attempt: Int?,
        at: Int64?,
        error: V2UnknownErrorDTO?
    ) {
        self.attempt = attempt
        self.at = at
        self.error = error
    }
}

public nonisolated struct V2FileAttachmentDTO: Decodable, Sendable {
    public let uri: String?
    public let mime: String?
    public let name: String?
    public let description: String?
    public let data: String?
    public let source: V2FileSourceDTO?
    public let mention: V2PromptSourceDTO?

    private enum CodingKeys: String, CodingKey {
        case uri, mime, name, description, data, source, mention
    }

    public init(from decoder: Decoder) throws {
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

public nonisolated struct V2FileSourceDTO: Decodable, Sendable {
    public let type: String?
    public let uri: String?
    public let start: Int?
    public let end: Int?
    public let text: String?

    public init(
        type: String?,
        uri: String?,
        start: Int?,
        end: Int?,
        text: String?
    ) {
        self.type = type
        self.uri = uri
        self.start = start
        self.end = end
        self.text = text
    }
}

public nonisolated struct V2AgentAttachmentDTO: Decodable, Sendable {
    public let name: String?
    public let source: V2PromptSourceDTO?
    public let mention: V2PromptSourceDTO?

    private enum CodingKeys: String, CodingKey {
        case name, source, mention
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        source = try container.decodeIfPresent(V2PromptSourceDTO.self, forKey: .source)
        mention = try container.decodeIfPresent(V2PromptSourceDTO.self, forKey: .mention)
            ?? source
    }
}

public nonisolated struct V2UserSkillDTO: Codable, Sendable {
    public let id: String?
    public let name: String?
    public let mention: V2PromptSourceDTO?

    public init(
        id: String?,
        name: String?,
        mention: V2PromptSourceDTO?
    ) {
        self.id = id
        self.name = name
        self.mention = mention
    }
}

public nonisolated struct V2PromptSourceDTO: Codable, Sendable, Equatable {
    public let start: Int?
    public let end: Int?
    public let text: String?

    public init(start: Int?, end: Int?, text: String?) {
        self.start = start
        self.end = end
        self.text = text
    }
}

public nonisolated struct V2AssistantContentDTO: Decodable, Sendable {
    public let type: String
    public let id: String?
    public let text: String?
    public let name: String?
    public let state: V2ToolStateDTO?
    public let time: V2MessageTimeDTO?

    public init(
        type: String,
        id: String?,
        text: String?,
        name: String?,
        state: V2ToolStateDTO?,
        time: V2MessageTimeDTO?
    ) {
        self.type = type
        self.id = id
        self.text = text
        self.name = name
        self.state = state
        self.time = time
    }
}

public nonisolated struct V2ToolStateDTO: Decodable, Sendable {
    public let status: String?
    public let input: AnyCodable?
    public let content: [V2ToolContentDTO]
    public let error: V2UnknownErrorDTO?
    public let executed: Bool?
    public let structured: [String: AnyCodable]?
    public let metadata: [String: AnyCodable]?

    private enum CodingKeys: String, CodingKey {
        case status, input, content, error, executed, structured, metadata
    }

    public init(from decoder: Decoder) throws {
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

public nonisolated struct V2ToolContentDTO: Decodable, Sendable {
    public let type: String
    public let text: String?
    public let uri: String?
    public let mime: String?
    public let name: String?

    public init(
        type: String,
        text: String?,
        uri: String?,
        mime: String?,
        name: String?
    ) {
        self.type = type
        self.text = text
        self.uri = uri
        self.mime = mime
        self.name = name
    }
}
