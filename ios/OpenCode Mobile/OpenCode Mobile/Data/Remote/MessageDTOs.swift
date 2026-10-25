import Foundation

nonisolated struct MessageDTO: Codable, Sendable {
    let info: MessageInfoDTO
    let parts: [MessagePartDTO]
}

nonisolated struct MessageInfoDTO: Codable, Sendable {
    let id: String
    let sessionID: String?
    let role: String
    let agent: String?
    let mode: String?
    let model: MessageModelRefDTO?
    let modelID: String?
    let providerID: String?
    let variant: String?
    let time: MessageTimeDTO?
    let error: MessageErrorInfoDTO?
    let tokens: MessageTokensDTO?
    let cost: Double?
    let waiting: Bool?
    let delivery: String?
    let finish: String?
    /// v2 live prompt skills, projected from the inbox event.
    let skills: [V2UserSkillDTO]?
}

nonisolated struct MessageTokensDTO: Codable, Sendable {
    let input: Int64?
    let output: Int64?
    let reasoning: Int64?
    let cache: MessageTokenCacheDTO?
}

nonisolated struct MessageTokenCacheDTO: Codable, Sendable {
    let read: Int64?
    let write: Int64?
}

nonisolated struct MessageErrorInfoDTO: Codable, Sendable {
    let name: String?
    let data: MessageErrorDataDTO?
}

nonisolated struct MessageErrorDataDTO: Codable, Sendable {
    let message: String?
    let statusCode: Int?
    let responseBody: String?
    var isRetryable: Bool? = nil
    var providerID: String? = nil
    var retries: Int? = nil
    /// v2 `retryAfterMs`, carried here by the live event translation; v1 servers never send it.
    var retryAfterMs: Int64? = nil
}

nonisolated struct MessageModelRefDTO: Codable, Sendable {
    let modelID: String?
    let providerID: String?
}

nonisolated struct MessageTimeDTO: Codable, Sendable {
    let created: Int64?
    let completed: Int64?
}

nonisolated struct MessagePartDTO: Codable, Sendable {
    let id: String?
    let sessionID: String?
    let messageID: String?
    let type: String
    let text: String?
    let system: String?
    let tool: String?
    let source: [String: AnyCodable]?
    let callID: String?
    let state: MessageToolStateDTO?
    let snapshot: String?
    let reason: String?
    let name: String?
    let prompt: String?
    let description: String?
    let agent: String?
    let attempt: Int?
    let error: MessageErrorInfoDTO?
    let time: MessagePartTimeDTO?
    let synthetic: Bool?
    let ignored: Bool?
    let hash: String?
    let files: [String]?
    let auto: Bool?
    let completed: Bool?
    let summary: String?
    let subtask: String?
    let mime: String?
    let filename: String?
    let url: String?
    /// Client-only `notice` part from the v2 live projection.
    let notice: V2NoticeDTO?
}

nonisolated struct MessageToolStateDTO: Codable, Sendable {
    let status: String
    let input: [String: AnyCodable]?
    let raw: String?
    let title: String?
    let output: String?
    let error: String?
    let time: MessageToolTimeDTO?
    let metadata: [String: AnyCodable]?
    /// Set by the v2 live projection when `tool.failed` reports `error.type: "aborted"`.
    var interrupted: Bool? = nil
    /// Set when the server closed the call without running it (`executed: false`).
    var malformed: Bool? = nil
}

nonisolated struct MessageToolTimeDTO: Codable, Sendable {
    let start: Int64?
    let end: Int64?
    let compacted: Int64?
    let created: Int64?
}

nonisolated struct MessagePartTimeDTO: Codable, Sendable {
    let start: Int64?
    let end: Int64?
}

nonisolated struct SendMessageRequestDTO: Codable, Sendable {
    let parts: [SendMessagePartDTO]
    let model: SendPromptModelDTO?
    let variant: String?
    let agent: String?
    let messageID: String?

    init(
        parts: [SendMessagePartDTO],
        model: SendPromptModelDTO? = nil,
        variant: String? = nil,
        agent: String? = nil,
        messageID: String? = nil
    ) {
        self.parts = parts
        self.model = model
        self.variant = variant
        self.agent = agent
        self.messageID = messageID
    }
}

nonisolated struct SendPromptModelDTO: Codable, Sendable {
    let providerID: String
    let modelID: String
}

nonisolated struct SendMessagePartDTO: Codable, Sendable {
    let id: String?
    let type: String
    let text: String?
    let mime: String?
    let filename: String?
    let url: String?
    let name: String?
    let source: AgentPartSourceDTO?
    /// Project-file mention. Written by the prompt body writer; not a device attachment.
    let fileSource: FileReferenceSourceDTO?

    init(
        id: String? = nil,
        type: String,
        text: String? = nil,
        mime: String? = nil,
        filename: String? = nil,
        url: String? = nil,
        name: String? = nil,
        source: AgentPartSourceDTO? = nil,
        fileSource: FileReferenceSourceDTO? = nil
    ) {
        self.id = id
        self.type = type
        self.text = text
        self.mime = mime
        self.filename = filename
        self.url = url
        self.name = name
        self.source = source
        self.fileSource = fileSource
    }

    private enum CodingKeys: String, CodingKey {
        case id, type, text, mime, filename, url, name, source
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id)
        type = try container.decode(String.self, forKey: .type)
        text = try container.decodeIfPresent(String.self, forKey: .text)
        mime = try container.decodeIfPresent(String.self, forKey: .mime)
        filename = try container.decodeIfPresent(String.self, forKey: .filename)
        url = try container.decodeIfPresent(String.self, forKey: .url)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        source = try container.decodeIfPresent(AgentPartSourceDTO.self, forKey: .source)
        fileSource = nil
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(id, forKey: .id)
        try container.encode(type, forKey: .type)
        try container.encodeIfPresent(text, forKey: .text)
        try container.encodeIfPresent(mime, forKey: .mime)
        try container.encodeIfPresent(filename, forKey: .filename)
        try container.encodeIfPresent(url, forKey: .url)
        try container.encodeIfPresent(name, forKey: .name)
        try container.encodeIfPresent(source, forKey: .source)
    }
}

nonisolated struct FileReferenceSourceDTO: Sendable, Equatable {
    let path: String
    let value: String
    let start: Int
    let end: Int
}

nonisolated struct AgentPartSourceDTO: Codable, Sendable, Equatable {
    let value: String
    let start: Int
    let end: Int
}

nonisolated struct V2PromptRequestDTO: Codable, Sendable {
    let id: String?
    let text: String
    let files: [V2PromptFileDTO]?
    let agents: [V2PromptAgentDTO]?
    let skills: [V2PromptSkillDTO]?
    let delivery: String
    /// Project-file mentions. Written into `files` as literal URIs, after device attachments.
    let fileMentions: [V2PromptFileMentionDTO]?

    init(
        id: String? = nil,
        text: String,
        files: [V2PromptFileDTO]? = nil,
        agents: [V2PromptAgentDTO]? = nil,
        skills: [V2PromptSkillDTO]? = nil,
        delivery: String = "steer",
        fileMentions: [V2PromptFileMentionDTO]? = nil
    ) {
        self.id = id
        self.text = text
        self.files = files
        self.agents = agents
        self.skills = skills
        self.delivery = delivery
        self.fileMentions = fileMentions
    }

    private enum CodingKeys: String, CodingKey {
        case id, text, files, agents, skills, delivery
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id)
        text = try container.decode(String.self, forKey: .text)
        files = try container.decodeIfPresent([V2PromptFileDTO].self, forKey: .files)
        agents = try container.decodeIfPresent([V2PromptAgentDTO].self, forKey: .agents)
        skills = try container.decodeIfPresent([V2PromptSkillDTO].self, forKey: .skills)
        delivery = try container.decode(String.self, forKey: .delivery)
        fileMentions = nil
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(id, forKey: .id)
        try container.encode(text, forKey: .text)
        try container.encodeIfPresent(files, forKey: .files)
        try container.encodeIfPresent(agents, forKey: .agents)
        try container.encodeIfPresent(skills, forKey: .skills)
        try container.encode(delivery, forKey: .delivery)
    }
}

nonisolated struct V2PromptFileMentionDTO: Sendable, Equatable {
    let uri: String
    let name: String
    let mention: V2PromptSourceDTO
}

nonisolated struct V2PromptSkillDTO: Codable, Sendable, Equatable {
    let id: String
    let mention: V2PromptSourceDTO?

    init(id: String, mention: V2PromptSourceDTO? = nil) {
        self.id = id
        self.mention = mention
    }
}

nonisolated struct V2PromptFileDTO: Codable, Sendable {
    let uri: String
    let name: String?
    /// Used to build a data URL from a local file. Not a wire field.
    let mime: String?

    init(uri: String, name: String? = nil, mime: String? = nil) {
        self.uri = uri
        self.name = name
        self.mime = mime
    }

    private enum CodingKeys: String, CodingKey {
        case uri
        case name
        case mime
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        uri = try container.decode(String.self, forKey: .uri)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        mime = try container.decodeIfPresent(String.self, forKey: .mime)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(uri, forKey: .uri)
        try container.encodeIfPresent(name, forKey: .name)
    }
}

nonisolated struct V2PromptAgentDTO: Codable, Sendable {
    let name: String
    let mention: V2PromptSourceDTO?
}

/// v1 `POST /session/{id}/shell`. The server records the run under `agent` and `model`.
nonisolated struct V1ShellRequestDTO: Codable, Sendable {
    let agent: String
    let command: String
    let model: SendPromptModelDTO?
}

/// v2 `POST /api/session/{id}/shell`; output streams back as `session.shell.*` events.
nonisolated struct V2ShellRequestDTO: Codable, Sendable {
    let command: String
}

nonisolated struct V2SwitchAgentRequestDTO: Codable, Sendable {
    let agent: String
}

nonisolated struct V2SwitchModelRequestDTO: Codable, Sendable {
    let model: V2PromptModelRefDTO
}

nonisolated struct V2PromptModelRefDTO: Codable, Sendable {
    let id: String
    let providerID: String
    let variant: String?
}

/// A type-erased Codable value for flexible JSON content.
nonisolated enum AnyCodable: Codable, Sendable, Equatable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case dictionary([String: AnyCodable])
    case array([AnyCodable])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let v = try? container.decode(String.self) { self = .string(v) }
        else if let v = try? container.decode(Int.self) { self = .int(v) }
        else if let v = try? container.decode(Double.self) { self = .double(v) }
        else if let v = try? container.decode(Bool.self) { self = .bool(v) }
        else if let v = try? container.decode([String: AnyCodable].self) { self = .dictionary(v) }
        else if let v = try? container.decode([AnyCodable].self) { self = .array(v) }
        else { throw DecodingError.dataCorruptedError(in: container, debugDescription: "AnyCodable: unexpected value") }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let v): try container.encode(v)
        case .int(let v): try container.encode(v)
        case .double(let v): try container.encode(v)
        case .bool(let v): try container.encode(v)
        case .dictionary(let v): try container.encode(v)
        case .array(let v): try container.encode(v)
        case .null: try container.encodeNil()
        }
    }
}

extension AnyCodable {
    var stringValue: String? {
        if case let .string(s) = self { s } else { nil }
    }
}
