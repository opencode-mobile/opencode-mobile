import Foundation

public struct SessionDTO: Codable, Sendable {
    public let id: String
    public let title: String
    public let slug: String?
    public let projectID: String?
    public let directory: String?
    public let parentID: String?
    public let version: String?
    public let cost: Double?
    public let tokens: MessageTokensDTO?
    public let time: SessionTimeDTO?
    public let revert: SessionRevertDTO?

    public init(
        id: String,
        title: String,
        slug: String?,
        projectID: String?,
        directory: String?,
        parentID: String?,
        version: String?,
        cost: Double?,
        tokens: MessageTokensDTO?,
        time: SessionTimeDTO?,
        revert: SessionRevertDTO?
    ) {
        self.id = id
        self.title = title
        self.slug = slug
        self.projectID = projectID
        self.directory = directory
        self.parentID = parentID
        self.version = version
        self.cost = cost
        self.tokens = tokens
        self.time = time
        self.revert = revert
    }
}

public struct SessionRevertDTO: Codable, Sendable {
    public let messageID: String
    public let partID: String?
    public let snapshot: String?
    public let diff: String?

    public init(messageID: String, partID: String? = nil, snapshot: String? = nil, diff: String? = nil) {
        self.messageID = messageID
        self.partID = partID
        self.snapshot = snapshot
        self.diff = diff
    }
}

public struct SessionTimeDTO: Codable, Sendable {
    public let created: Int64?
    public let updated: Int64?
    public let archived: Int64?
    /// v2 only.
    public let idle: Int64?
    /// v2 only.
    public let viewed: Int64?

    public init(
        created: Int64? = nil,
        updated: Int64? = nil,
        archived: Int64? = nil,
        idle: Int64? = nil,
        viewed: Int64? = nil
    ) {
        self.created = created
        self.updated = updated
        self.archived = archived
        self.idle = idle
        self.viewed = viewed
    }
}

public struct CreateSessionRequestDTO: Codable, Sendable {
    public let parentID: String?
    public let title: String?

    public init(
        parentID: String?,
        title: String?
    ) {
        self.parentID = parentID
        self.title = title
    }
}

public struct UpdateSessionTimeDTO: Codable, Sendable {
    public let archived: Int64?

    public init(archived: Int64?) {
        self.archived = archived
    }
}

public struct UpdateSessionRequestDTO: Codable, Sendable {
    public let title: String?
    public let time: UpdateSessionTimeDTO?

    public init(title: String? = nil, time: UpdateSessionTimeDTO? = nil) {
        self.title = title
        self.time = time
    }
}

public struct ForkSessionRequestDTO: Codable, Sendable {
    public let messageID: String?

    public init(messageID: String?) {
        self.messageID = messageID
    }
}

public struct InitSessionRequestDTO: Codable, Sendable {
    public let messageID: String
    public let providerID: String
    public let modelID: String

    public init(
        messageID: String,
        providerID: String,
        modelID: String
    ) {
        self.messageID = messageID
        self.providerID = providerID
        self.modelID = modelID
    }
}

public struct SummarizeSessionRequestDTO: Codable, Sendable {
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

public struct RevertSessionRequestDTO: Codable, Sendable {
    public let messageID: String
    public let partID: String?

    public init(
        messageID: String,
        partID: String?
    ) {
        self.messageID = messageID
        self.partID = partID
    }
}

public struct PermissionResponseRequestDTO: Codable, Sendable {
    public let reply: String
    /// Note sent with a `reject`; the key is left out when nil.
    public let message: String?

    public init(reply: String, message: String? = nil) {
        self.reply = reply
        self.message = message
    }
}

public struct PermissionRequestDTO: Codable, Sendable {
    public let id: String
    public let sessionID: String
    public let permission: String
    public let patterns: [String]
    public let metadata: [String: AnyCodable]
    public let always: [String]
    public let tool: PermissionToolDTO?

    public init(
        id: String,
        sessionID: String,
        permission: String,
        patterns: [String],
        metadata: [String: AnyCodable],
        always: [String],
        tool: PermissionToolDTO?
    ) {
        self.id = id
        self.sessionID = sessionID
        self.permission = permission
        self.patterns = patterns
        self.metadata = metadata
        self.always = always
        self.tool = tool
    }
}

public struct PermissionToolDTO: Codable, Sendable {
    public let messageID: String
    public let callID: String

    public init(
        messageID: String,
        callID: String
    ) {
        self.messageID = messageID
        self.callID = callID
    }
}

public nonisolated struct QuestionReplyRequestDTO: Codable, Sendable {
    public let answers: [[String]]

    public init(answers: [[String]]) {
        self.answers = answers
    }
}

public nonisolated struct QuestionRequestDTO: Codable, Sendable {
    public let id: String
    public let sessionID: String
    public let questions: [QuestionInfoDTO]
    public let tool: QuestionToolDTO?

    public init(
        id: String,
        sessionID: String,
        questions: [QuestionInfoDTO],
        tool: QuestionToolDTO?
    ) {
        self.id = id
        self.sessionID = sessionID
        self.questions = questions
        self.tool = tool
    }
}

public nonisolated struct QuestionInfoDTO: Codable, Sendable {
    public let question: String
    public let header: String
    public let options: [QuestionOptionDTO]
    public let multiple: Bool?
    public let custom: Bool?

    public init(
        question: String,
        header: String,
        options: [QuestionOptionDTO],
        multiple: Bool?,
        custom: Bool?
    ) {
        self.question = question
        self.header = header
        self.options = options
        self.multiple = multiple
        self.custom = custom
    }
}

public nonisolated struct QuestionOptionDTO: Codable, Sendable {
    public let label: String
    public let description: String?

    public init(
        label: String,
        description: String?
    ) {
        self.label = label
        self.description = description
    }
}

public nonisolated struct V2FormInfoDTO: Codable, Sendable {
    public let id: String
    public let sessionID: String
    public let title: String?
    public let metadata: V2FormMetadataDTO?
    public let fields: [V2FormFieldDTO]

    public init(
        id: String,
        sessionID: String,
        title: String? = nil,
        metadata: V2FormMetadataDTO? = nil,
        fields: [V2FormFieldDTO] = []
    ) {
        self.id = id
        self.sessionID = sessionID
        self.title = title
        self.metadata = metadata
        self.fields = fields
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try container.decode(String.self, forKey: .sessionID)
        title = try container.decodeIfPresent(String.self, forKey: .title)
        metadata = try container.decodeIfPresent(V2FormMetadataDTO.self, forKey: .metadata)
        fields = try container.decodeIfPresent([V2FormFieldDTO].self, forKey: .fields) ?? []
    }
}

public nonisolated struct V2FormMetadataDTO: Codable, Sendable {
    public let kind: String?
    public let tool: QuestionToolDTO?

    public init(
        kind: String?,
        tool: QuestionToolDTO?
    ) {
        self.kind = kind
        self.tool = tool
    }
}

public nonisolated struct V2FormFieldDTO: Codable, Sendable {
    public let key: String
    public let title: String?
    public let description: String?
    public let type: String?
    public let options: [V2FormFieldOptionDTO]
    public let custom: Bool?
    public let multiple: Bool?
    public let minItems: Int?
    public let maxItems: Int?

    public init(
        key: String,
        title: String? = nil,
        description: String? = nil,
        type: String? = nil,
        options: [V2FormFieldOptionDTO] = [],
        custom: Bool? = nil,
        multiple: Bool? = nil,
        minItems: Int? = nil,
        maxItems: Int? = nil
    ) {
        self.key = key
        self.title = title
        self.description = description
        self.type = type
        self.options = options
        self.custom = custom
        self.multiple = multiple
        self.minItems = minItems
        self.maxItems = maxItems
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        key = try container.decode(String.self, forKey: .key)
        title = try container.decodeIfPresent(String.self, forKey: .title)
        description = try container.decodeIfPresent(String.self, forKey: .description)
        type = try container.decodeIfPresent(String.self, forKey: .type)
        options = try container.decodeIfPresent([V2FormFieldOptionDTO].self, forKey: .options) ?? []
        custom = try container.decodeIfPresent(Bool.self, forKey: .custom)
        multiple = try container.decodeIfPresent(Bool.self, forKey: .multiple)
        minItems = try container.decodeIfPresent(Int.self, forKey: .minItems)
        maxItems = try container.decodeIfPresent(Int.self, forKey: .maxItems)
    }
}

public nonisolated struct V2FormFieldOptionDTO: Codable, Sendable {
    public let value: String?
    public let label: String?
    public let description: String?

    public init(
        value: String?,
        label: String?,
        description: String?
    ) {
        self.value = value
        self.label = label
        self.description = description
    }
}

public nonisolated struct V2FormReplyRequestDTO: Codable, Sendable {
    public let answer: [String: JSONValueDTO]

    public init(answer: [String: JSONValueDTO]) {
        self.answer = answer
    }
}

public nonisolated struct QuestionToolDTO: Codable, Sendable {
    public let messageID: String
    public let callID: String?

    private enum CodingKeys: String, CodingKey {
        case messageID, callID, id
    }

    public init(messageID: String, callID: String? = nil) {
        self.messageID = messageID
        self.callID = callID
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        messageID = try container.decode(String.self, forKey: .messageID)
        callID = try container.decodeIfPresent(String.self, forKey: .callID)
            ?? container.decodeIfPresent(String.self, forKey: .id)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(messageID, forKey: .messageID)
        try container.encodeIfPresent(callID, forKey: .callID)
    }
}

public struct TodoDTO: Codable, Sendable {
    public let content: String
    public let status: String
    public let priority: String

    public init(
        content: String,
        status: String,
        priority: String
    ) {
        self.content = content
        self.status = status
        self.priority = priority
    }
}

public nonisolated struct FileDiffDTO: Decodable, Sendable {
    public let file: String
    public let patch: String?
    public let before: String?
    public let after: String?
    public let additions: Int
    public let deletions: Int
    public let status: String?

    private enum CodingKeys: String, CodingKey {
        case file
        case path
        case patch
        case before
        case after
        case additions
        case deletions
        case status
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        file = try container.decodeIfPresent(String.self, forKey: .file)
            ?? container.decode(String.self, forKey: .path)
        patch = try container.decodeIfPresent(String.self, forKey: .patch)
        before = try container.decodeIfPresent(String.self, forKey: .before)
        after = try container.decodeIfPresent(String.self, forKey: .after)
        additions = try container.decodeIfPresent(Int.self, forKey: .additions) ?? 0
        deletions = try container.decodeIfPresent(Int.self, forKey: .deletions) ?? 0
        status = try container.decodeIfPresent(String.self, forKey: .status)
    }
}

public nonisolated struct VcsFileStatusDTO: Decodable, Sendable {
    public let file: String
    public let additions: Int
    public let deletions: Int
    public let status: String?

    private enum CodingKeys: String, CodingKey {
        case file, additions, deletions, status
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        file = try container.decode(String.self, forKey: .file)
        additions = try container.decodeIfPresent(Int.self, forKey: .additions) ?? 0
        deletions = try container.decodeIfPresent(Int.self, forKey: .deletions) ?? 0
        status = try container.decodeIfPresent(String.self, forKey: .status)
    }
}

public struct VcsInfoDTO: Decodable, Sendable {
    public let branch: String?
    public let defaultBranch: String?

    public init(
        branch: String?,
        defaultBranch: String?
    ) {
        self.branch = branch
        self.defaultBranch = defaultBranch
    }

    private enum CodingKeys: String, CodingKey {
        case branch
        case defaultBranch = "default_branch"
    }
}
