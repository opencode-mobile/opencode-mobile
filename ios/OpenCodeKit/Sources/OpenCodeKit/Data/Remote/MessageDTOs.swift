import Foundation

public nonisolated struct MessageDTO: Codable, Sendable {
    public let info: MessageInfoDTO
    public let parts: [MessagePartDTO]

    public init(
        info: MessageInfoDTO,
        parts: [MessagePartDTO]
    ) {
        self.info = info
        self.parts = parts
    }
}

public nonisolated struct MessageInfoDTO: Codable, Sendable {
    public let id: String
    public let sessionID: String?
    public let role: String
    public let agent: String?
    public let mode: String?
    public let model: MessageModelRefDTO?
    public let modelID: String?
    public let providerID: String?
    public let variant: String?
    public let time: MessageTimeDTO?
    public let error: MessageErrorInfoDTO?
    public let tokens: MessageTokensDTO?
    public let cost: Double?
    public let waiting: Bool?
    public let delivery: String?
    public let finish: String?
    /// v2 live prompt skills, projected from the inbox event.
    public let skills: [V2UserSkillDTO]?

    public init(
        id: String,
        sessionID: String?,
        role: String,
        agent: String?,
        mode: String?,
        model: MessageModelRefDTO?,
        modelID: String?,
        providerID: String?,
        variant: String?,
        time: MessageTimeDTO?,
        error: MessageErrorInfoDTO?,
        tokens: MessageTokensDTO?,
        cost: Double?,
        waiting: Bool?,
        delivery: String?,
        finish: String?,
        skills: [V2UserSkillDTO]?
    ) {
        self.id = id
        self.sessionID = sessionID
        self.role = role
        self.agent = agent
        self.mode = mode
        self.model = model
        self.modelID = modelID
        self.providerID = providerID
        self.variant = variant
        self.time = time
        self.error = error
        self.tokens = tokens
        self.cost = cost
        self.waiting = waiting
        self.delivery = delivery
        self.finish = finish
        self.skills = skills
    }
}

public nonisolated struct MessageTokensDTO: Codable, Sendable {
    public let input: Int64?
    public let output: Int64?
    public let reasoning: Int64?
    public let cache: MessageTokenCacheDTO?

    public init(
        input: Int64?,
        output: Int64?,
        reasoning: Int64?,
        cache: MessageTokenCacheDTO?
    ) {
        self.input = input
        self.output = output
        self.reasoning = reasoning
        self.cache = cache
    }
}

public nonisolated struct MessageTokenCacheDTO: Codable, Sendable {
    public let read: Int64?
    public let write: Int64?

    public init(
        read: Int64?,
        write: Int64?
    ) {
        self.read = read
        self.write = write
    }
}

public nonisolated struct MessageErrorInfoDTO: Codable, Sendable {
    public let name: String?
    public let data: MessageErrorDataDTO?

    public init(
        name: String?,
        data: MessageErrorDataDTO?
    ) {
        self.name = name
        self.data = data
    }
}

public nonisolated struct MessageErrorDataDTO: Codable, Sendable {
    public let message: String?
    public let statusCode: Int?
    public let responseBody: String?
    public var isRetryable: Bool? = nil
    public var providerID: String? = nil
    public var retries: Int? = nil
    /// v2 `retryAfterMs`, carried here by the live event translation; v1 servers never send it.
    public var retryAfterMs: Int64? = nil

    public init(
        message: String?,
        statusCode: Int?,
        responseBody: String?,
        isRetryable: Bool? = nil,
        providerID: String? = nil,
        retries: Int? = nil,
        retryAfterMs: Int64? = nil
    ) {
        self.message = message
        self.statusCode = statusCode
        self.responseBody = responseBody
        self.isRetryable = isRetryable
        self.providerID = providerID
        self.retries = retries
        self.retryAfterMs = retryAfterMs
    }
}

public nonisolated struct MessageModelRefDTO: Codable, Sendable {
    public let modelID: String?
    public let providerID: String?

    public init(
        modelID: String?,
        providerID: String?
    ) {
        self.modelID = modelID
        self.providerID = providerID
    }
}

public nonisolated struct MessageTimeDTO: Codable, Sendable {
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

public nonisolated struct MessagePartDTO: Codable, Sendable {
    public let id: String?
    public let sessionID: String?
    public let messageID: String?
    public let type: String
    public let text: String?
    public let system: String?
    public let tool: String?
    public let source: [String: AnyCodable]?
    public let callID: String?
    public let state: MessageToolStateDTO?
    public let snapshot: String?
    public let reason: String?
    public let name: String?
    public let prompt: String?
    public let description: String?
    public let agent: String?
    public let attempt: Int?
    public let error: MessageErrorInfoDTO?
    public let time: MessagePartTimeDTO?
    public let synthetic: Bool?
    public let ignored: Bool?
    public let hash: String?
    public let files: [String]?
    public let auto: Bool?
    public let completed: Bool?
    public let summary: String?
    public let subtask: String?
    public let mime: String?
    public let filename: String?
    public let url: String?
    /// Client-only: absolute server path of an uploaded file (live `metadata.attachments`).
    public let serverPath: String?
    /// Client-only `notice` part from the v2 live projection.
    public let notice: V2NoticeDTO?

    public init(
        id: String?,
        sessionID: String?,
        messageID: String?,
        type: String,
        text: String?,
        system: String?,
        tool: String?,
        source: [String: AnyCodable]?,
        callID: String?,
        state: MessageToolStateDTO?,
        snapshot: String?,
        reason: String?,
        name: String?,
        prompt: String?,
        description: String?,
        agent: String?,
        attempt: Int?,
        error: MessageErrorInfoDTO?,
        time: MessagePartTimeDTO?,
        synthetic: Bool?,
        ignored: Bool?,
        hash: String?,
        files: [String]?,
        auto: Bool?,
        completed: Bool?,
        summary: String?,
        subtask: String?,
        mime: String?,
        filename: String?,
        url: String?,
        serverPath: String?,
        notice: V2NoticeDTO?
    ) {
        self.id = id
        self.sessionID = sessionID
        self.messageID = messageID
        self.type = type
        self.text = text
        self.system = system
        self.tool = tool
        self.source = source
        self.callID = callID
        self.state = state
        self.snapshot = snapshot
        self.reason = reason
        self.name = name
        self.prompt = prompt
        self.description = description
        self.agent = agent
        self.attempt = attempt
        self.error = error
        self.time = time
        self.synthetic = synthetic
        self.ignored = ignored
        self.hash = hash
        self.files = files
        self.auto = auto
        self.completed = completed
        self.summary = summary
        self.subtask = subtask
        self.mime = mime
        self.filename = filename
        self.url = url
        self.serverPath = serverPath
        self.notice = notice
    }
}

public nonisolated struct MessageToolStateDTO: Codable, Sendable {
    public let status: String
    public let input: [String: AnyCodable]?
    public let raw: String?
    public let title: String?
    public let output: String?
    public let error: String?
    public let time: MessageToolTimeDTO?
    public let metadata: [String: AnyCodable]?
    /// Set by the v2 live projection when `tool.failed` reports `error.type: "aborted"`
    /// or `"permission.rejected"`.
    public var interrupted: Bool? = nil
    /// Set by the v2 live projection when `tool.failed` reports `error.type: "permission.rejected"`;
    /// `declineNote` is the note the user sent with Deny.
    public var declined: Bool? = nil
    public var declineNote: String? = nil
    /// Set when the server closed the call without running it (`executed: false`).
    public var malformed: Bool? = nil
    /// Files the completed tool returned (v1 `attachments`); the v2 live projection fills it
    /// from `content` file items.
    public var attachments: [MessageToolAttachmentDTO]? = nil

    public init(
        status: String,
        input: [String: AnyCodable]?,
        raw: String?,
        title: String?,
        output: String?,
        error: String?,
        time: MessageToolTimeDTO?,
        metadata: [String: AnyCodable]?,
        interrupted: Bool? = nil,
        declined: Bool? = nil,
        declineNote: String? = nil,
        malformed: Bool? = nil,
        attachments: [MessageToolAttachmentDTO]? = nil
    ) {
        self.status = status
        self.input = input
        self.raw = raw
        self.title = title
        self.output = output
        self.error = error
        self.time = time
        self.metadata = metadata
        self.interrupted = interrupted
        self.declined = declined
        self.declineNote = declineNote
        self.malformed = malformed
        self.attachments = attachments
    }
}

/// A v1 tool attachment: a file part whose `url` is normally a `data:` URL.
public nonisolated struct MessageToolAttachmentDTO: Codable, Sendable {
    public let id: String?
    public let mime: String?
    public let filename: String?
    public let url: String?

    public init(
        id: String?,
        mime: String?,
        filename: String?,
        url: String?
    ) {
        self.id = id
        self.mime = mime
        self.filename = filename
        self.url = url
    }
}

public nonisolated struct MessageToolTimeDTO: Codable, Sendable {
    public let start: Int64?
    public let end: Int64?
    public let compacted: Int64?
    public let created: Int64?

    public init(
        start: Int64?,
        end: Int64?,
        compacted: Int64?,
        created: Int64?
    ) {
        self.start = start
        self.end = end
        self.compacted = compacted
        self.created = created
    }
}

public nonisolated struct MessagePartTimeDTO: Codable, Sendable {
    public let start: Int64?
    public let end: Int64?

    public init(
        start: Int64?,
        end: Int64?
    ) {
        self.start = start
        self.end = end
    }
}

public nonisolated struct SendMessageRequestDTO: Codable, Sendable {
    public let parts: [SendMessagePartDTO]
    public let model: SendPromptModelDTO?
    public let variant: String?
    public let agent: String?
    public let messageID: String?

    public init(
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

public nonisolated struct SendPromptModelDTO: Codable, Sendable {
    public let providerID: String
    public let modelID: String

    public init(
        providerID: String,
        modelID: String
    ) {
        self.providerID = providerID
        self.modelID = modelID
    }
}

public nonisolated struct SendMessagePartDTO: Codable, Sendable {
    public let id: String?
    public let type: String
    public let text: String?
    public let mime: String?
    public let filename: String?
    public let url: String?
    public let name: String?
    public let source: AgentPartSourceDTO?
    /// Project-file mention. Written by the prompt body writer; not a device attachment.
    public let fileSource: FileReferenceSourceDTO?

    public init(
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

    public init(from decoder: Decoder) throws {
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

    public func encode(to encoder: Encoder) throws {
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

public nonisolated struct FileReferenceSourceDTO: Sendable, Equatable {
    public let path: String
    public let value: String
    public let start: Int
    public let end: Int

    public init(
        path: String,
        value: String,
        start: Int,
        end: Int
    ) {
        self.path = path
        self.value = value
        self.start = start
        self.end = end
    }
}

public nonisolated struct AgentPartSourceDTO: Codable, Sendable, Equatable {
    public let value: String
    public let start: Int
    public let end: Int

    public init(
        value: String,
        start: Int,
        end: Int
    ) {
        self.value = value
        self.start = start
        self.end = end
    }
}

public nonisolated struct V2PromptMetadataDTO: Codable, Sendable, Equatable {
    public let attachments: [V2PromptUploadedAttachmentDTO]

    public init(attachments: [V2PromptUploadedAttachmentDTO]) {
        self.attachments = attachments
    }
}

public nonisolated struct V2PromptUploadedAttachmentDTO: Codable, Sendable, Equatable {
    public let name: String
    public let mime: String
    public let path: String

    public init(
        name: String,
        mime: String,
        path: String
    ) {
        self.name = name
        self.mime = mime
        self.path = path
    }
}

public nonisolated struct V2PromptRequestDTO: Codable, Sendable {
    public let id: String?
    public let text: String
    public let files: [V2PromptFileDTO]?
    public let agents: [V2PromptAgentDTO]?
    public let skills: [V2PromptSkillDTO]?
    public let delivery: String
    /// Project-file mentions. Written into `files` as literal URIs, after device attachments.
    public let fileMentions: [V2PromptFileMentionDTO]?
    /// `{ attachments: [{ name, mime, path }] }` for files uploaded to the server (#644).
    public let metadata: V2PromptMetadataDTO?

    public init(
        id: String? = nil,
        text: String,
        files: [V2PromptFileDTO]? = nil,
        agents: [V2PromptAgentDTO]? = nil,
        skills: [V2PromptSkillDTO]? = nil,
        delivery: String = "steer",
        fileMentions: [V2PromptFileMentionDTO]? = nil,
        metadata: V2PromptMetadataDTO? = nil
    ) {
        self.id = id
        self.text = text
        self.files = files
        self.agents = agents
        self.skills = skills
        self.delivery = delivery
        self.fileMentions = fileMentions
        self.metadata = metadata
    }

    private enum CodingKeys: String, CodingKey {
        case id, text, files, agents, skills, delivery, metadata
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id)
        text = try container.decode(String.self, forKey: .text)
        files = try container.decodeIfPresent([V2PromptFileDTO].self, forKey: .files)
        agents = try container.decodeIfPresent([V2PromptAgentDTO].self, forKey: .agents)
        skills = try container.decodeIfPresent([V2PromptSkillDTO].self, forKey: .skills)
        delivery = try container.decode(String.self, forKey: .delivery)
        fileMentions = nil
        metadata = try container.decodeIfPresent(V2PromptMetadataDTO.self, forKey: .metadata)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(id, forKey: .id)
        try container.encode(text, forKey: .text)
        try container.encodeIfPresent(files, forKey: .files)
        try container.encodeIfPresent(agents, forKey: .agents)
        try container.encodeIfPresent(skills, forKey: .skills)
        try container.encode(delivery, forKey: .delivery)
        try container.encodeIfPresent(metadata, forKey: .metadata)
    }
}

public nonisolated struct V2PromptFileMentionDTO: Sendable, Equatable {
    public let uri: String
    public let name: String
    public let mention: V2PromptSourceDTO

    public init(
        uri: String,
        name: String,
        mention: V2PromptSourceDTO
    ) {
        self.uri = uri
        self.name = name
        self.mention = mention
    }
}

public nonisolated struct V2PromptSkillDTO: Codable, Sendable, Equatable {
    public let id: String
    public let mention: V2PromptSourceDTO?

    public init(id: String, mention: V2PromptSourceDTO? = nil) {
        self.id = id
        self.mention = mention
    }
}

public nonisolated struct V2PromptFileDTO: Codable, Sendable {
    public let uri: String
    public let name: String?
    /// Used to build a data URL from a local file. Not a wire field.
    public let mime: String?

    public init(uri: String, name: String? = nil, mime: String? = nil) {
        self.uri = uri
        self.name = name
        self.mime = mime
    }

    private enum CodingKeys: String, CodingKey {
        case uri
        case name
        case mime
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        uri = try container.decode(String.self, forKey: .uri)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        mime = try container.decodeIfPresent(String.self, forKey: .mime)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(uri, forKey: .uri)
        try container.encodeIfPresent(name, forKey: .name)
    }
}

public nonisolated struct V2PromptAgentDTO: Codable, Sendable {
    public let name: String
    public let mention: V2PromptSourceDTO?

    public init(
        name: String,
        mention: V2PromptSourceDTO?
    ) {
        self.name = name
        self.mention = mention
    }
}

/// v1 `POST /session/{id}/shell`. The server records the run under `agent` and `model`.
public nonisolated struct V1ShellRequestDTO: Codable, Sendable {
    public let agent: String
    public let command: String
    public let model: SendPromptModelDTO?

    public init(
        agent: String,
        command: String,
        model: SendPromptModelDTO?
    ) {
        self.agent = agent
        self.command = command
        self.model = model
    }
}

/// v2 `POST /api/session/{id}/shell`; output streams back as `session.shell.*` events.
public nonisolated struct V2ShellRequestDTO: Codable, Sendable {
    public let command: String

    public init(command: String) {
        self.command = command
    }
}

public nonisolated struct V2SwitchAgentRequestDTO: Codable, Sendable {
    public let agent: String

    public init(agent: String) {
        self.agent = agent
    }
}

public nonisolated struct V2SwitchModelRequestDTO: Codable, Sendable {
    public let model: V2PromptModelRefDTO

    public init(model: V2PromptModelRefDTO) {
        self.model = model
    }
}

public nonisolated struct V2PromptModelRefDTO: Codable, Sendable {
    public let id: String
    public let providerID: String
    public let variant: String?

    public init(
        id: String,
        providerID: String,
        variant: String?
    ) {
        self.id = id
        self.providerID = providerID
        self.variant = variant
    }
}

/// A type-erased Codable value for flexible JSON content.
public nonisolated enum AnyCodable: Codable, Sendable, Equatable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case dictionary([String: AnyCodable])
    case array([AnyCodable])
    case null

    public init(from decoder: Decoder) throws {
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

    public func encode(to encoder: Encoder) throws {
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
    public var stringValue: String? {
        if case let .string(s) = self { s } else { nil }
    }
}
