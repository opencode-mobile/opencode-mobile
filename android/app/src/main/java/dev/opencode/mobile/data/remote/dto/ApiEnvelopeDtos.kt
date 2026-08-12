package dev.opencode.mobile.data.remote.dto

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
