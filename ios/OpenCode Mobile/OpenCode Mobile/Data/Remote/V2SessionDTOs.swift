import Foundation

nonisolated struct V2LocationDTO: Codable, Sendable {
    let directory: String
    let workspaceID: String?

    init(directory: String, workspaceID: String? = nil) {
        self.directory = directory
        self.workspaceID = workspaceID
    }
}

nonisolated struct V2SessionDTO: Decodable, Sendable {
    let id: String
    let title: String
    let projectID: String?
    let parentID: String?
    let cost: Double?
    let tokens: MessageTokensDTO?
    let time: SessionTimeDTO?
    let location: V2LocationDTO?
    let revert: SessionRevertDTO?
}

nonisolated struct V2CreateSessionRequestDTO: Encodable, Sendable {
    let location: V2LocationDTO?
}

nonisolated struct V2RenameSessionRequestDTO: Encodable, Sendable {
    let title: String
}

nonisolated struct V2ForkSessionRequestDTO: Encodable, Sendable {
    let messageID: String?
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
