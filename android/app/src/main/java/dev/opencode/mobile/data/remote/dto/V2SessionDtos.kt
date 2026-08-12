package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class V2LocationDto(
    val directory: String,
    @SerialName("workspaceID") val workspaceId: String? = null,
)

@Serializable
data class V2SessionDto(
    val id: String,
    val title: String,
    @SerialName("projectID") val projectId: String? = null,
    @SerialName("parentID") val parentId: String? = null,
    val cost: Double? = null,
    val tokens: MessageTokensDto? = null,
    val time: SessionTimeDto? = null,
    val location: V2LocationDto? = null,
    val revert: SessionRevertDto? = null,
)

@Serializable
data class V2CreateSessionRequestDto(
    val location: V2LocationDto? = null,
)

@Serializable
data class V2RenameSessionRequestDto(
    val title: String,
)

@Serializable
data class V2ForkSessionRequestDto(
    @SerialName("messageID") val messageId: String? = null,
)
