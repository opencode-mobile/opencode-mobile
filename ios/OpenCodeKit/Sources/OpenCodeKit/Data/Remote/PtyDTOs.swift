import Foundation

public nonisolated struct PtyDTO: Decodable, Sendable {
    public let id: String
    public let title: String
    public let command: String
    public let cwd: String
    public let status: String

    private enum CodingKeys: String, CodingKey {
        case id
        case title
        case command
        case cwd
        case status
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        title = try container.decodeIfPresent(String.self, forKey: .title) ?? ""
        command = try container.decodeIfPresent(String.self, forKey: .command) ?? ""
        cwd = try container.decodeIfPresent(String.self, forKey: .cwd) ?? ""
        status = try container.decodeIfPresent(String.self, forKey: .status) ?? ""
    }
}

public nonisolated struct CreatePtyRequestDTO: Encodable, Sendable {
    public let cwd: String

    public init(cwd: String) {
        self.cwd = cwd
    }
}

public nonisolated struct UpdatePtyRequestDTO: Encodable, Sendable {
    public let size: PtySizeDTO

    public init(size: PtySizeDTO) {
        self.size = size
    }
}

public nonisolated struct PtySizeDTO: Encodable, Sendable {
    public let rows: Int
    public let cols: Int

    public init(
        rows: Int,
        cols: Int
    ) {
        self.rows = rows
        self.cols = cols
    }
}
