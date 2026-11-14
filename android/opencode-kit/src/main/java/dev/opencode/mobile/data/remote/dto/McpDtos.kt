package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.Serializable

/** v1 `GET /mcp` map value: `{status, error?}`. */
@Serializable
data class McpStatusDto(
    val status: String? = null,
    val error: String? = null,
)

/** v2 `GET /api/mcp` array element. */
@Serializable
data class V2McpServerDto(
    val name: String,
    val status: V2McpStatusDto = V2McpStatusDto(),
)

@Serializable
data class V2McpStatusDto(
    val status: String? = null,
    val error: String? = null,
)
