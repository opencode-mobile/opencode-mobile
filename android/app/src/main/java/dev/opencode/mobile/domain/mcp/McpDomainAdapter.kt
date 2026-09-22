package dev.opencode.mobile.domain.mcp

import dev.opencode.mobile.data.remote.dto.McpStatusDto
import dev.opencode.mobile.data.remote.dto.V2McpServerDto
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.JsonConvertException

class McpDomainAdapter {
    /** v1 `GET /mcp`: name → status map. */
    fun toServers(statuses: Map<String, McpStatusDto>): List<McpServer> =
        statuses
            .map { (name, dto) -> server(name, dto.status, dto.error) }
            .sortedBy { it.name.lowercase() }

    /** v2 `GET /api/mcp`: array of servers with nested status. */
    fun toServersFromV2(dtos: List<V2McpServerDto>): List<McpServer> =
        dtos
            .map { dto -> server(dto.name, dto.status.status, dto.status.error) }
            .sortedBy { it.name.lowercase() }

    private fun server(
        name: String,
        wireStatus: String?,
        error: String?,
    ): McpServer {
        val status =
            when (wireStatus?.lowercase()) {
                "connected" -> McpServerStatus.Connected
                "pending" -> McpServerStatus.Connecting
                "disabled" -> McpServerStatus.Disabled
                "needs_auth", "needs_client_registration" -> McpServerStatus.NeedsSignIn
                else -> McpServerStatus.Failed
            }
        return McpServer(
            name = name,
            status = status,
            errorMessage = error?.takeIf { it.isNotBlank() && status != McpServerStatus.Connected },
        )
    }

    fun mapError(throwable: Throwable): McpError =
        when (throwable) {
            is McpError -> throwable
            is ResponseException ->
                McpError.Http(
                    statusCode = throwable.response.status.value,
                    message = throwable.message,
                )
            is JsonConvertException -> McpError.Decoding(throwable)
            else -> McpError.Transport(throwable)
        }
}
