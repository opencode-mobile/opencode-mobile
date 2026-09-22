import Foundation

struct McpDomainAdapter: Sendable {
    /// v1 `GET /mcp`: name → status map.
    func toServers(_ statuses: [String: McpStatusDTO]) -> [McpServer] {
        statuses
            .map { name, dto in server(name: name, wireStatus: dto.status, error: dto.error) }
            .sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    /// v2 `GET /api/mcp`: array of servers with nested status.
    func toServers(_ dtos: [V2McpServerDTO]) -> [McpServer] {
        dtos
            .map { dto in server(name: dto.name, wireStatus: dto.status?.status, error: dto.status?.error) }
            .sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    private func server(name: String, wireStatus: String?, error: String?) -> McpServer {
        let status: McpServerStatus =
            switch wireStatus?.lowercased() {
            case "connected": .connected
            case "pending": .connecting
            case "disabled": .disabled
            case "needs_auth", "needs_client_registration": .needsSignIn
            default: .failed
            }
        let trimmed = error?.trimmingCharacters(in: .whitespacesAndNewlines)
        return McpServer(
            name: name,
            status: status,
            errorMessage: (trimmed?.isEmpty == false && status != .connected) ? trimmed : nil
        )
    }

    func mapError(_ error: Error) -> McpError {
        if let mcpError = error as? McpError {
            return mcpError
        }
        if let apiError = error as? McpAPIError {
            switch apiError {
            case .invalidResponse, .noActiveConnection:
                return .transport("Invalid response")
            case let .httpStatus(code, message):
                return .http(statusCode: code, message: message)
            }
        }
        if error is DecodingError {
            return .decoding(error.localizedDescription)
        }
        return .transport(error.localizedDescription)
    }
}
