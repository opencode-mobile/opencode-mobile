package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class V2LocationDto(
    val directory: String = "",
    @SerialName("workspaceID") val workspaceId: String? = null,
)

@Serializable
data class V2SessionDto(
    val id: String,
    val title: String = "",
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

@Serializable
data class V2PermissionRequestDto(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val action: String,
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val metadata: JsonObject = JsonObject(emptyMap()),
    val source: V2PermissionSourceDto? = null,
)

@Serializable
data class V2PermissionSourceDto(
    val type: String? = null,
    @SerialName("messageID") val messageId: String? = null,
    @SerialName("callID") val callId: String? = null,
)

@Serializable
data class V2PermissionReplyRequestDto(
    val reply: String,
)

@Serializable
data class V2CompactSessionRequestDto(
    val delivery: String,
)

@Serializable
data class V2RevertStageRequestDto(
    @SerialName("messageID") val messageId: String,
    @SerialName("partID") val partId: String? = null,
    val files: Boolean,
)

@Serializable
data class V2VcsInfoDto(
    val branch: V2VcsBranchDto? = null,
)

@Serializable
data class V2VcsBranchDto(
    val current: String? = null,
    val default: String? = null,
)
