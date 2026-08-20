import Foundation

struct SessionDTO: Codable, Sendable {
    let id: String
    let title: String
    let slug: String?
    let projectID: String?
    let directory: String?
    let parentID: String?
    let version: String?
    let cost: Double?
    let tokens: MessageTokensDTO?
    let time: SessionTimeDTO?
    let revert: SessionRevertDTO?
}

struct SessionRevertDTO: Codable, Sendable {
    let messageID: String
    let partID: String?
    let snapshot: String?
    let diff: String?

    init(messageID: String, partID: String? = nil, snapshot: String? = nil, diff: String? = nil) {
        self.messageID = messageID
        self.partID = partID
        self.snapshot = snapshot
        self.diff = diff
    }
}

struct SessionTimeDTO: Codable, Sendable {
    let created: Int64?
    let updated: Int64?
    let archived: Int64?

    init(created: Int64? = nil, updated: Int64? = nil, archived: Int64? = nil) {
        self.created = created
        self.updated = updated
        self.archived = archived
    }
}

struct CreateSessionRequestDTO: Codable, Sendable {
    let parentID: String?
    let title: String?
}

struct UpdateSessionTimeDTO: Codable, Sendable {
    let archived: Int64?
}

struct UpdateSessionRequestDTO: Codable, Sendable {
    let title: String?
    let time: UpdateSessionTimeDTO?

    init(title: String? = nil, time: UpdateSessionTimeDTO? = nil) {
        self.title = title
        self.time = time
    }
}

struct ForkSessionRequestDTO: Codable, Sendable {
    let messageID: String?
}

struct InitSessionRequestDTO: Codable, Sendable {
    let messageID: String
    let providerID: String
    let modelID: String
}

struct SummarizeSessionRequestDTO: Codable, Sendable {
    let providerID: String
    let modelID: String
}

struct RevertSessionRequestDTO: Codable, Sendable {
    let messageID: String
    let partID: String?
}

struct PermissionResponseRequestDTO: Codable, Sendable {
    let response: String
}

struct PermissionRequestDTO: Codable, Sendable {
    let id: String
    let sessionID: String
    let permission: String
    let patterns: [String]
    let metadata: [String: AnyCodable]
    let always: [String]
    let tool: PermissionToolDTO?
}

struct PermissionToolDTO: Codable, Sendable {
    let messageID: String
    let callID: String
}

nonisolated struct QuestionReplyRequestDTO: Codable, Sendable {
    let answers: [[String]]
}

nonisolated struct QuestionRequestDTO: Codable, Sendable {
    let id: String
    let sessionID: String
    let questions: [QuestionInfoDTO]
    let tool: QuestionToolDTO?
}

nonisolated struct QuestionInfoDTO: Codable, Sendable {
    let question: String
    let header: String
    let options: [QuestionOptionDTO]
    let multiple: Bool?
    let custom: Bool?
}

nonisolated struct QuestionOptionDTO: Codable, Sendable {
    let label: String
    let description: String?
}

nonisolated struct V2FormInfoDTO: Codable, Sendable {
    let id: String
    let sessionID: String
    let title: String?
    let metadata: V2FormMetadataDTO?
    let fields: [V2FormFieldDTO]

    init(
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

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        sessionID = try container.decode(String.self, forKey: .sessionID)
        title = try container.decodeIfPresent(String.self, forKey: .title)
        metadata = try container.decodeIfPresent(V2FormMetadataDTO.self, forKey: .metadata)
        fields = try container.decodeIfPresent([V2FormFieldDTO].self, forKey: .fields) ?? []
    }
}

nonisolated struct V2FormMetadataDTO: Codable, Sendable {
    let kind: String?
    let tool: QuestionToolDTO?
}

nonisolated struct V2FormFieldDTO: Codable, Sendable {
    let key: String
    let title: String?
    let description: String?
    let type: String?
    let options: [V2FormFieldOptionDTO]
    let custom: Bool?
    let multiple: Bool?

    init(
        key: String,
        title: String? = nil,
        description: String? = nil,
        type: String? = nil,
        options: [V2FormFieldOptionDTO] = [],
        custom: Bool? = nil,
        multiple: Bool? = nil
    ) {
        self.key = key
        self.title = title
        self.description = description
        self.type = type
        self.options = options
        self.custom = custom
        self.multiple = multiple
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        key = try container.decode(String.self, forKey: .key)
        title = try container.decodeIfPresent(String.self, forKey: .title)
        description = try container.decodeIfPresent(String.self, forKey: .description)
        type = try container.decodeIfPresent(String.self, forKey: .type)
        options = try container.decodeIfPresent([V2FormFieldOptionDTO].self, forKey: .options) ?? []
        custom = try container.decodeIfPresent(Bool.self, forKey: .custom)
        multiple = try container.decodeIfPresent(Bool.self, forKey: .multiple)
    }
}

nonisolated struct V2FormFieldOptionDTO: Codable, Sendable {
    let value: String?
    let label: String?
    let description: String?
}

nonisolated struct V2FormReplyRequestDTO: Codable, Sendable {
    let answer: [String: JSONValueDTO]
}

nonisolated struct QuestionToolDTO: Codable, Sendable {
    let messageID: String
    let callID: String?

    private enum CodingKeys: String, CodingKey {
        case messageID, callID, id
    }

    init(messageID: String, callID: String? = nil) {
        self.messageID = messageID
        self.callID = callID
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        messageID = try container.decode(String.self, forKey: .messageID)
        callID = try container.decodeIfPresent(String.self, forKey: .callID)
            ?? container.decodeIfPresent(String.self, forKey: .id)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(messageID, forKey: .messageID)
        try container.encodeIfPresent(callID, forKey: .callID)
    }
}

struct TodoDTO: Codable, Sendable {
    let content: String
    let status: String
    let priority: String
}

nonisolated struct FileDiffDTO: Decodable, Sendable {
    let file: String
    let patch: String?
    let before: String?
    let after: String?
    let additions: Int
    let deletions: Int
    let status: String?

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

    init(from decoder: Decoder) throws {
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

struct VcsInfoDTO: Decodable, Sendable {
    let branch: String?
    let defaultBranch: String?

    private enum CodingKeys: String, CodingKey {
        case branch
        case defaultBranch = "default_branch"
    }
}
