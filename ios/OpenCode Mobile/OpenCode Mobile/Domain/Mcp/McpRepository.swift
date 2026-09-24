import Foundation

protocol McpRepository: Sendable {
    /// Configured MCP servers with connection status, sorted by name.
    func listServers(directory: String?) async throws -> [McpServer]
}

struct DefaultMcpRepository: McpRepository, Sendable {
    private let api: McpAPIClient
    private let adapter: McpDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager?

    init(
        api: McpAPIClient,
        adapter: McpDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
    }

    func listServers(directory: String?) async throws -> [McpServer] {
        do {
            if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
                return adapter.toServers(try await api.listServersV2(directory: directory))
            }
            return adapter.toServers(try await api.listServers(directory: directory))
        } catch {
            throw adapter.mapError(error)
        }
    }
}

struct EmptyMcpRepository: McpRepository, Sendable {
    func listServers(directory: String?) async throws -> [McpServer] { [] }
}
