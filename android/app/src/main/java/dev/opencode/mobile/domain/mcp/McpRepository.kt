package dev.opencode.mobile.domain.mcp

interface McpRepository {
    /** Configured MCP servers with connection status, sorted by name. */
    suspend fun listServers(directory: String? = null): List<McpServer>
}
