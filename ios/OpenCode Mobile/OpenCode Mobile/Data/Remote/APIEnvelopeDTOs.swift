import Foundation

nonisolated struct APIDataEnvelope<T: Decodable & Sendable>: Decodable, Sendable {
    let data: T

    private enum CodingKeys: String, CodingKey {
        case data
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if container.contains(.data) {
            data = try container.decode(T.self, forKey: .data)
        } else {
            data = try T(from: decoder)
        }
    }
}

nonisolated struct APIListEnvelope<T: Decodable & Sendable>: Decodable, Sendable {
    let data: [T]
    let cursor: APICursorDTO?

    private enum CodingKeys: String, CodingKey {
        case data
        case cursor
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        data = try container.decodeIfPresent([T].self, forKey: .data) ?? []
        cursor = try container.decodeIfPresent(APICursorDTO.self, forKey: .cursor)
    }
}

nonisolated struct APICursorDTO: Decodable, Sendable {
    let previous: String?
    let next: String?
}

nonisolated struct APIActiveSessionsEnvelope: Decodable, Sendable {
    let data: [String: APISessionActiveDTO]

    private enum CodingKeys: String, CodingKey {
        case data
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        data = try container.decodeIfPresent([String: APISessionActiveDTO].self, forKey: .data) ?? [:]
    }
}

nonisolated struct APISessionActiveDTO: Decodable, Sendable {
    let type: String?
    var attempt: Int? = nil
    var message: String? = nil
    var next: Int64? = nil
    var error: APISessionRetryErrorDTO? = nil
    var action: APISessionRetryActionDTO? = nil
}

nonisolated struct APISessionRetryErrorDTO: Decodable, Sendable {
    let type: String?
}

nonisolated struct APISessionRetryActionDTO: Decodable, Sendable {
    let label: String?
    let link: String?
}

nonisolated struct APILocatedEnvelope<T: Decodable & Sendable>: Decodable, Sendable {
    let location: V2LocationInfoDTO?
    let data: T
}

nonisolated struct V2LocationInfoDTO: Decodable, Sendable {
    let directory: String
    let workspaceID: String?
    let project: V2LocationProjectDTO?
}

nonisolated struct V2LocationProjectDTO: Decodable, Sendable {
    let id: String?
    let directory: String?
    let canonical: String?
}
