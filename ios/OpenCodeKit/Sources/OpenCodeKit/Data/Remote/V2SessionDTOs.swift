import Foundation

public nonisolated struct V2LocationDTO: Codable, Sendable {
    public let directory: String
    public let workspaceID: String?

    public init(directory: String, workspaceID: String? = nil) {
        self.directory = directory
        self.workspaceID = workspaceID
    }

    private enum CodingKeys: String, CodingKey {
        case directory
        case workspaceID
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        directory = try container.decodeIfPresent(String.self, forKey: .directory) ?? ""
        workspaceID = try container.decodeIfPresent(String.self, forKey: .workspaceID)
    }
}

public nonisolated struct V2SessionForkDTO: Decodable, Sendable {
    public let sessionID: String?

    public init(sessionID: String?) {
        self.sessionID = sessionID
    }
}

public nonisolated struct V2SessionDTO: Decodable, Sendable {
    public let id: String
    public let title: String
    public let projectID: String?
    public let parentID: String?
    public let fork: V2SessionForkDTO?
    public let cost: Double?
    public let tokens: MessageTokensDTO?
    public let time: SessionTimeDTO?
    public let location: V2LocationDTO?
    public let revert: SessionRevertDTO?
    /// How the latest run ended: `succeeded` | `failed` | `interrupted`.
    public let outcome: String?
    /// The agent a subagent session runs (`general`, `explore`).
    public let agent: String?

    private enum CodingKeys: String, CodingKey {
        case id
        case title
        case projectID
        case parentID
        case fork
        case cost
        case tokens
        case time
        case location
        case revert
        case outcome
        case agent
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        title = try container.decodeIfPresent(String.self, forKey: .title) ?? ""
        projectID = try container.decodeIfPresent(String.self, forKey: .projectID)
        parentID = try container.decodeIfPresent(String.self, forKey: .parentID)
        fork = try container.decodeIfPresent(V2SessionForkDTO.self, forKey: .fork)
        cost = try container.decodeIfPresent(Double.self, forKey: .cost)
        tokens = try container.decodeIfPresent(MessageTokensDTO.self, forKey: .tokens)
        time = try container.decodeIfPresent(SessionTimeDTO.self, forKey: .time)
        location = try container.decodeIfPresent(V2LocationDTO.self, forKey: .location)
        revert = try container.decodeIfPresent(SessionRevertDTO.self, forKey: .revert)
        outcome = try? container.decodeIfPresent(String.self, forKey: .outcome)
        agent = try? container.decodeIfPresent(String.self, forKey: .agent)
    }
}

/// Body for `POST /api/session/{id}/view`.
public nonisolated struct V2ViewSessionRequestDTO: Encodable, Sendable {
    public let idle: Int64

    public init(idle: Int64) {
        self.idle = idle
    }
}

public nonisolated struct V2MoveSessionRequestDTO: Encodable, Sendable {
    public let directory: String

    public init(directory: String) {
        self.directory = directory
    }
}

public nonisolated struct V2SessionGenerateRequestDTO: Encodable, Sendable {
    public let prompt: String

    public init(prompt: String) {
        self.prompt = prompt
    }
}

/// Body of `PUT /api/experimental/session/{id}/instructions/entries/{key}`.
public nonisolated struct V2InstructionEntryRequestDTO: Encodable, Sendable {
    public let value: String

    public init(value: String) {
        self.value = value
    }
}

/// `data` of `POST /api/session/{id}/interrupt`.
public nonisolated struct V2InterruptResultDTO: Decodable, Sendable {
    /// True when a run was stopped, false when the session was already idle.
    public let interrupted: Bool?

    public init(interrupted: Bool?) {
        self.interrupted = interrupted
    }
}

public nonisolated struct V2SessionGenerateResponseDTO: Decodable, Sendable {
    public let text: String

    public init(text: String) {
        self.text = text
    }
}

public nonisolated struct V2CreateSessionRequestDTO: Encodable, Sendable {
    public let parentID: String?
    public let title: String?
    public let location: V2LocationDTO?
    /// The new session's model and reasoning level.
    public let model: V2PromptModelRefDTO?

    public init(parentID: String? = nil, title: String? = nil, location: V2LocationDTO? = nil, model: V2PromptModelRefDTO? = nil) {
        self.parentID = parentID
        self.title = title
        self.location = location
        self.model = model
    }

    private enum CodingKeys: String, CodingKey {
        case parentID
        case title
        case location
        case model
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(parentID, forKey: .parentID)
        try container.encodeIfPresent(title, forKey: .title)
        try container.encodeIfPresent(location, forKey: .location)
        try container.encodeIfPresent(model, forKey: .model)
    }
}

public nonisolated struct V2RenameSessionRequestDTO: Encodable, Sendable {
    public let title: String

    public init(title: String) {
        self.title = title
    }
}

/// `PATCH /api/session/{id}` body that archives (`true`) or restores (`false`) a session.
public nonisolated struct V2ArchiveSessionRequestDTO: Encodable, Sendable {
    public let archived: Bool

    public init(archived: Bool) {
        self.archived = archived
    }
}

public nonisolated struct V2InboxDeliveryRequestDTO: Encodable, Sendable {
    public let delivery: String

    public init(delivery: String) {
        self.delivery = delivery
    }
}

public nonisolated struct V2ForkSessionRequestDTO: Encodable, Sendable {
    public let before: String?

    public init(before: String? = nil) {
        self.before = before
    }

    private enum CodingKeys: String, CodingKey {
        case before
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(before, forKey: .before)
    }
}

public nonisolated struct V2PermissionRequestDTO: Decodable, Sendable {
    public let id: String
    public let sessionID: String
    public let action: String
    public let resources: [String]
    public let save: [String]
    public let metadata: [String: AnyCodable]
    public let source: V2PermissionSourceDTO?

    private enum CodingKeys: String, CodingKey {
        case id
        case sessionID
        case action
        case resources
        case save
        case metadata
        case source
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try container.decode(String.self, forKey: .sessionID)
        action = try container.decode(String.self, forKey: .action)
        resources = try container.decodeIfPresent([String].self, forKey: .resources) ?? []
        save = try container.decodeIfPresent([String].self, forKey: .save) ?? []
        metadata = try container.decodeIfPresent([String: AnyCodable].self, forKey: .metadata) ?? [:]
        source = try container.decodeIfPresent(V2PermissionSourceDTO.self, forKey: .source)
    }
}

public nonisolated struct V2PermissionSourceDTO: Decodable, Sendable {
    public let type: String?
    public let messageID: String?
    public let callID: String?

    private enum CodingKeys: String, CodingKey {
        case type, messageID, callID, id
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decodeIfPresent(String.self, forKey: .type)
        messageID = try container.decodeIfPresent(String.self, forKey: .messageID)
        callID = try container.decodeIfPresent(String.self, forKey: .callID)
            ?? container.decodeIfPresent(String.self, forKey: .id)
    }
}

public nonisolated struct V2PermissionReplyRequestDTO: Encodable, Sendable {
    public let decision: String
    /// Note sent with a `reject`. The v2 body allows no extra properties, so the key is left out when nil.
    public let message: String?

    public init(decision: String, message: String? = nil) {
        self.decision = decision
        self.message = message
    }
}

public nonisolated struct V2CompactSessionRequestDTO: Encodable, Sendable {
    public let delivery: String

    public init(delivery: String = "steer") {
        self.delivery = delivery
    }
}

public nonisolated struct V2RevertStageRequestDTO: Encodable, Sendable {
    public let messageID: String
    public let files: Bool

    public init(messageID: String, files: Bool = true) {
        self.messageID = messageID
        self.files = files
    }
}

public nonisolated struct V2VcsInfoDTO: Decodable, Sendable {
    /// `git` once the server has detected the repository. A cold server first answers
    /// `{"branch":{}}` with no provider while detection warms up.
    public let provider: String?
    public let branch: V2VcsBranchDTO?

    public init(
        provider: String?,
        branch: V2VcsBranchDTO?
    ) {
        self.provider = provider
        self.branch = branch
    }
}

public nonisolated struct V2VcsBranchDTO: Decodable, Sendable {
    public let current: String?
    public let `default`: String?

    public init(current: String?, default: String?) {
        self.current = current
        self.`default` = `default`
    }
}

public nonisolated struct V2InboxItemDTO: Decodable, Sendable, Equatable {
    public let id: String
    public let sessionID: String?
    public let timeCreated: Int64
    public let type: String
    public let payload: V2InboxUserPayloadDTO?
    public let delivery: String?

    public init(
        id: String,
        sessionID: String? = nil,
        timeCreated: Int64 = 0,
        type: String = "",
        payload: V2InboxUserPayloadDTO? = nil,
        delivery: String? = nil
    ) {
        self.id = id
        self.sessionID = sessionID
        self.timeCreated = timeCreated
        self.type = type
        self.payload = payload
        self.delivery = delivery
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case sessionID
        case timeCreated
        case type
        case payload
        case delivery
        case time
    }

    private enum TimeKeys: String, CodingKey {
        case created
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try container.decodeIfPresent(String.self, forKey: .sessionID)
        // `Session.Inbox.User` nests the enqueue time as `time.created`.
        let time = try? container.nestedContainer(keyedBy: TimeKeys.self, forKey: .time)
        let nestedCreated = try? time?.decodeIfPresent(Int64.self, forKey: .created)
        let flatCreated = try container.decodeIfPresent(Int64.self, forKey: .timeCreated)
        timeCreated = nestedCreated ?? flatCreated ?? 0
        type = try container.decodeIfPresent(String.self, forKey: .type) ?? ""
        payload = try? container.decodeIfPresent(V2InboxUserPayloadDTO.self, forKey: .payload) ?? nil
        delivery = try container.decodeIfPresent(String.self, forKey: .delivery)
    }
}

public nonisolated struct V2InboxUserPayloadDTO: Decodable, Sendable, Equatable {
    public let text: String

    public init(text: String = "") {
        self.text = text
    }

    private enum CodingKeys: String, CodingKey {
        case text
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        text = try container.decodeIfPresent(String.self, forKey: .text) ?? ""
    }
}
