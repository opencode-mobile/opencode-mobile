package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiDataEnvelope<T>(
    val data: T,
)

@Serializable
data class ApiListEnvelope<T>(
    val data: List<T> = emptyList(),
    val cursor: ApiCursorDto? = null,
)

@Serializable
data class ApiCursorDto(
    val previous: String? = null,
    val next: String? = null,
)

@Serializable
data class ApiActiveSessionsEnvelope(
    val data: Map<String, ApiSessionActiveDto> = emptyMap(),
)

@Serializable
data class ApiSessionActiveDto(
    val type: String? = null,
)

@Serializable
data class ApiLocatedEnvelope<T>(
    val location: V2LocationInfoDto? = null,
    val data: T,
)

@Serializable
data class V2LocationInfoDto(
    val directory: String,
    @SerialName("workspaceID") val workspaceId: String? = null,
    val project: V2LocationProjectDto? = null,
)

@Serializable
data class V2LocationProjectDto(
    val id: String? = null,
    val directory: String? = null,
    val canonical: String? = null,
)
