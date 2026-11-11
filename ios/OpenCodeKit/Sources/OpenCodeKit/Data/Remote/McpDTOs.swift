import Foundation

/// v1 `GET /mcp` map value: `{status, error?}`.
public nonisolated struct McpStatusDTO: Decodable, Sendable {
    public let status: String?
    public let error: String?

    public init(
        status: String?,
        error: String?
    ) {
        self.status = status
        self.error = error
    }
}

/// v2 `GET /api/mcp` array element.
public nonisolated struct V2McpServerDTO: Decodable, Sendable {
    public let name: String
    public let status: V2McpStatusDTO?

    public init(
        name: String,
        status: V2McpStatusDTO?
    ) {
        self.name = name
        self.status = status
    }
}

public nonisolated struct V2McpStatusDTO: Decodable, Sendable {
    public let status: String?
    public let error: String?

    public init(
        status: String?,
        error: String?
    ) {
        self.status = status
        self.error = error
    }
}
