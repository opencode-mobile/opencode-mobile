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
    /// How the latest run ended: `succeeded` | `failed` | `interrupted`.
    let outcome: String?

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
        outcome = try? container.decodeIfPresent(String.self, forKey: .outcome)
    }
}

/// Body for `POST /api/session/{id}/view`.
nonisolated struct V2ViewSessionRequestDTO: Encodable, Sendable {
    let idle: Int64
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

nonisolated struct V2InboxDeliveryRequestDTO: Encodable, Sendable {
    let delivery: String
}

nonisolated struct V2ForkSessionRequestDTO: Encodable, Sendable {
    let before: String?

    init(before: String? = nil) {
        self.before = before
    }

    private enum CodingKeys: String, CodingKey {
        case before
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(before, forKey: .before)
    }
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

    private enum CodingKeys: String, CodingKey {
        case type, messageID, callID, id
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decodeIfPresent(String.self, forKey: .type)
        messageID = try container.decodeIfPresent(String.self, forKey: .messageID)
        callID = try container.decodeIfPresent(String.self, forKey: .callID)
            ?? container.decodeIfPresent(String.self, forKey: .id)
    }
}

nonisolated struct V2PermissionReplyRequestDTO: Encodable, Sendable {
    let decision: String
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

nonisolated struct V2InboxItemDTO: Decodable, Sendable, Equatable {
    let id: String
    let sessionID: String?
    let timeCreated: Int64
    let type: String
    let payload: V2InboxUserPayloadDTO?
    let delivery: String?

    init(
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

    init(from decoder: Decoder) throws {
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

nonisolated struct V2InboxUserPayloadDTO: Decodable, Sendable, Equatable {
    let text: String

    init(text: String = "") {
        self.text = text
    }

    private enum CodingKeys: String, CodingKey {
        case text
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        text = try container.decodeIfPresent(String.self, forKey: .text) ?? ""
    }
}
