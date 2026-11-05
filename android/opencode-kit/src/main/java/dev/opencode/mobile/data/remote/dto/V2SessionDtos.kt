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
data class V2SessionForkDto(
    @SerialName("sessionID") val sessionId: String? = null,
)

@Serializable
data class V2SessionDto(
    val id: String,
    val title: String = "",
    @SerialName("projectID") val projectId: String? = null,
    @SerialName("parentID") val parentId: String? = null,
    val fork: V2SessionForkDto? = null,
    val cost: Double? = null,
    val tokens: MessageTokensDto? = null,
    val time: SessionTimeDto? = null,
    val location: V2LocationDto? = null,
    val revert: SessionRevertDto? = null,
    /** `succeeded` | `failed` | `interrupted`; kept raw so unknown values never fail decoding. */
    val outcome: String? = null,
    /** The agent a subagent session runs (`general`, `explore`). */
    val agent: String? = null,
)

@Serializable
data class V2SessionViewRequestDto(
    val idle: Long,
)

@Serializable
data class V2CreateSessionRequestDto(
    @SerialName("parentID") val parentId: String? = null,
    val title: String? = null,
    val location: V2LocationDto? = null,
)

@Serializable
data class V2RenameSessionRequestDto(
    val title: String,
)

/** `PATCH /api/session/{id}` body that archives (`true`) or restores (`false`) a session. */
@Serializable
data class V2ArchiveSessionRequestDto(
    val archived: Boolean,
)

@Serializable
data class V2InboxDeliveryRequestDto(
    val delivery: String,
)

@Serializable
data class V2ForkSessionRequestDto(
    val before: String? = null,
)

/** `POST /api/session/{id}/move`: the destination directory only; the server's default delivery (steer) applies. */
@Serializable
data class V2MoveSessionRequestDto(
    val directory: String,
)

/** `POST /api/session/{id}/generate`: the prompt answered from the session's context. */
@Serializable
data class V2SessionGenerateRequestDto(
    val prompt: String,
)

@Serializable
data class V2SessionGenerateResponseDto(
    val text: String,
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
    val id: String? = null,
)

@Serializable
data class V2PermissionReplyRequestDto(
    val decision: String,
)

@Serializable
data class V2CompactSessionRequestDto(
    val delivery: String,
)

@Serializable
data class V2RevertStageRequestDto(
    @SerialName("messageID") val messageId: String,
    val files: Boolean,
)

@Serializable
data class V2VcsInfoDto(
    val provider: String? = null,
    val branch: V2VcsBranchDto? = null,
)

@Serializable
data class V2VcsBranchDto(
    val current: String? = null,
    val default: String? = null,
)

@Serializable
data class V2InboxItemDto(
    val id: String,
    @SerialName("sessionID") val sessionId: String? = null,
    val timeCreated: Long = 0,
    val type: String = "",
    val payload: V2InboxUserPayloadDto? = null,
    val delivery: String? = null,
    /** `Session.Inbox.User` nests the enqueue time as `time.created`. */
    val time: SessionTimeDto? = null,
) {
    val createdAtEpochMs: Long get() = time?.created ?: timeCreated
}

@Serializable
data class V2InboxUserPayloadDto(
    val text: String = "",
    val metadata: JsonObject? = null,
)
