import Foundation

nonisolated struct APIDataEnvelope<T: Decodable & Sendable>: Decodable, Sendable {
    let data: T
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
}
