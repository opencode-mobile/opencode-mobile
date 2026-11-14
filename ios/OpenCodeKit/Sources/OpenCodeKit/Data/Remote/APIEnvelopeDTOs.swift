import Foundation

public nonisolated struct APIDataEnvelope<T: Decodable & Sendable>: Decodable, Sendable {
    public let data: T

    private enum CodingKeys: String, CodingKey {
        case data
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if container.contains(.data) {
            data = try container.decode(T.self, forKey: .data)
        } else {
            data = try T(from: decoder)
        }
    }
}

public nonisolated struct APIListEnvelope<T: Decodable & Sendable>: Decodable, Sendable {
    public let data: [T]
    public let cursor: APICursorDTO?

    private enum CodingKeys: String, CodingKey {
        case data
        case cursor
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        data = try container.decodeIfPresent([T].self, forKey: .data) ?? []
        cursor = try container.decodeIfPresent(APICursorDTO.self, forKey: .cursor)
    }
}

public nonisolated struct APICursorDTO: Decodable, Sendable {
    public let previous: String?
    public let next: String?

    public init(
        previous: String?,
        next: String?
    ) {
        self.previous = previous
        self.next = next
    }
}

public nonisolated struct APIActiveSessionsEnvelope: Decodable, Sendable {
    public let data: [String: APISessionActiveDTO]

    private enum CodingKeys: String, CodingKey {
        case data
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        data = try container.decodeIfPresent([String: APISessionActiveDTO].self, forKey: .data) ?? [:]
    }
}

public nonisolated struct APISessionActiveDTO: Decodable, Sendable {
    public let type: String?
    public var attempt: Int? = nil
    public var message: String? = nil
    public var next: Int64? = nil
    public var error: APISessionRetryErrorDTO? = nil
    public var action: APISessionRetryActionDTO? = nil

    public init(
        type: String?,
        attempt: Int? = nil,
        message: String? = nil,
        next: Int64? = nil,
        error: APISessionRetryErrorDTO? = nil,
        action: APISessionRetryActionDTO? = nil
    ) {
        self.type = type
        self.attempt = attempt
        self.message = message
        self.next = next
        self.error = error
        self.action = action
    }
}

public nonisolated struct APISessionRetryErrorDTO: Decodable, Sendable {
    public let type: String?

    public init(
        type: String?
    ) {
        self.type = type
    }
}

public nonisolated struct APISessionRetryActionDTO: Decodable, Sendable {
    public let label: String?
    public let link: String?

    public init(
        label: String?,
        link: String?
    ) {
        self.label = label
        self.link = link
    }
}

public nonisolated struct APILocatedEnvelope<T: Decodable & Sendable>: Decodable, Sendable {
    public let location: V2LocationInfoDTO?
    public let data: T

    public init(
        location: V2LocationInfoDTO?,
        data: T
    ) {
        self.location = location
        self.data = data
    }
}

public nonisolated struct V2LocationInfoDTO: Decodable, Sendable {
    public let directory: String
    public let workspaceID: String?
    public let project: V2LocationProjectDTO?

    public init(
        directory: String,
        workspaceID: String?,
        project: V2LocationProjectDTO?
    ) {
        self.directory = directory
        self.workspaceID = workspaceID
        self.project = project
    }
}

public nonisolated struct V2LocationProjectDTO: Decodable, Sendable {
    public let id: String?
    public let directory: String?
    public let canonical: String?

    public init(
        id: String?,
        directory: String?,
        canonical: String?
    ) {
        self.id = id
        self.directory = directory
        self.canonical = canonical
    }
}
