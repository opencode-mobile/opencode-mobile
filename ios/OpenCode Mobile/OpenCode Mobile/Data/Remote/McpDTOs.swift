import Foundation

/// v1 `GET /mcp` map value: `{status, error?}`.
nonisolated struct McpStatusDTO: Decodable, Sendable {
    let status: String?
    let error: String?
}

/// v2 `GET /api/mcp` array element.
nonisolated struct V2McpServerDTO: Decodable, Sendable {
    let name: String
    let status: V2McpStatusDTO?
}

nonisolated struct V2McpStatusDTO: Decodable, Sendable {
    let status: String?
    let error: String?
}
