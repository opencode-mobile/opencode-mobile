import Foundation

nonisolated struct PtyDTO: Decodable, Sendable {
    let id: String
    let title: String
    let command: String
    let cwd: String
    let status: String

    private enum CodingKeys: String, CodingKey {
        case id
        case title
        case command
        case cwd
        case status
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        title = try container.decodeIfPresent(String.self, forKey: .title) ?? ""
        command = try container.decodeIfPresent(String.self, forKey: .command) ?? ""
        cwd = try container.decodeIfPresent(String.self, forKey: .cwd) ?? ""
        status = try container.decodeIfPresent(String.self, forKey: .status) ?? ""
    }
}

nonisolated struct CreatePtyRequestDTO: Encodable, Sendable {
    let cwd: String
}

nonisolated struct UpdatePtyRequestDTO: Encodable, Sendable {
    let size: PtySizeDTO
}

nonisolated struct PtySizeDTO: Encodable, Sendable {
    let rows: Int
    let cols: Int
}
