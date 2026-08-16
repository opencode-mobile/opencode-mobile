import Foundation

nonisolated struct V2LocationDTO: Codable, Sendable {
    let directory: String
    let workspaceID: String?

    init(directory: String, workspaceID: String? = nil) {
        self.directory = directory
        self.workspaceID = workspaceID
    }

    private enum CodingKeys: String, CodingKey {
        case directory
        case workspaceID
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        directory = try container.decodeIfPresent(String.self, forKey: .directory) ?? ""
        workspaceID = try container.decodeIfPresent(String.self, forKey: .workspaceID)
    }
}

nonisolated struct V2SessionForkDTO: Decodable, Sendable {
    let sessionID: String?
}

nonisolated struct V2SessionDTO: Decodable, Sendable {
    let id: String
    let title: String
    let projectID: String?
    let parentID: String?
    let fork: V2SessionForkDTO?
    let cost: Double?
    let tokens: MessageTokensDTO?
    let time: SessionTimeDTO?
    let location: V2LocationDTO?
    let revert: SessionRevertDTO?

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
    }

    init(from decoder: Decoder) throws {
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
    }
}

nonisolated struct V2CreateSessionRequestDTO: Encodable, Sendable {
    let title: String?
    let location: V2LocationDTO?

    init(title: String? = nil, location: V2LocationDTO? = nil) {
        self.title = title
        self.location = location
    }

    private enum CodingKeys: String, CodingKey {
        case title
        case location
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(title, forKey: .title)
        try container.encodeIfPresent(location, forKey: .location)
    }
}

nonisolated struct V2RenameSessionRequestDTO: Encodable, Sendable {
    let title: String
}

nonisolated struct V2ForkBoundaryDTO: Encodable, Sendable {
    let type: String
    let messageID: String?

    init(type: String, messageID: String? = nil) {
        self.type = type
        self.messageID = messageID
    }

    private enum CodingKeys: String, CodingKey {
        case type
        case messageID
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(type, forKey: .type)
        try container.encodeIfPresent(messageID, forKey: .messageID)
    }
}

nonisolated struct V2ForkSessionRequestDTO: Encodable, Sendable {
    let boundary: V2ForkBoundaryDTO
}

nonisolated struct V2PermissionRequestDTO: Decodable, Sendable {
    let id: String
    let sessionID: String
    let action: String
    let resources: [String]
    let save: [String]
    let metadata: [String: AnyCodable]
    let source: V2PermissionSourceDTO?

    private enum CodingKeys: String, CodingKey {
        case id
        case sessionID
        case action
        case resources
        case save
        case metadata
        case source
    }

    init(from decoder: Decoder) throws {
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

nonisolated struct V2PermissionSourceDTO: Decodable, Sendable {
    let type: String?
    let messageID: String?
    let callID: String?
}

nonisolated struct V2PermissionReplyRequestDTO: Encodable, Sendable {
    let reply: String
}

nonisolated struct V2CompactSessionRequestDTO: Encodable, Sendable {
    let delivery: String

    init(delivery: String = "steer") {
        self.delivery = delivery
    }
}

nonisolated struct V2RevertStageRequestDTO: Encodable, Sendable {
    let messageID: String
    let files: Bool

    init(messageID: String, files: Bool = true) {
        self.messageID = messageID
        self.files = files
    }
}

nonisolated struct V2VcsInfoDTO: Decodable, Sendable {
    let branch: V2VcsBranchDTO?
}

nonisolated struct V2VcsBranchDTO: Decodable, Sendable {
    let current: String?
    let `default`: String?
}
