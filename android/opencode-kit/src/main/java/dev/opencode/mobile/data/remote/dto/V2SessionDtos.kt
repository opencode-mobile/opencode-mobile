package dev.opencode.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

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
    /** The session's own model and level; prompts and generate use it. */
    val model: V2ModelRefDto? = null,
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
    /** Left out when null, so the server picks its default as before. */
    val model: V2ModelRefDto? = null,
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

/** `PUT /api/experimental/session/{id}/instructions/entries/{key}`: a string value is used as it is. */
@Serializable
data class V2InstructionEntryRequestDto(
    val value: String,
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
    /** What the approval is for, when the request has no resources to show. */
    val message: String? = null,
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
    /**
     * With `reject` only: the server hands it to the agent as the tool's error, and the run goes on; a reject without
     * it ends the run. Left out when null (OpenCode v2.2.5 ignores it with `once` and `always`).
     */
    val message: String? = null,
)

/** `POST /api/session/{id}/interrupt`: whether a run was stopped (`false` when the session was already idle). */
@Serializable
data class V2InterruptResultDto(
    val interrupted: Boolean = false,
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
    /** The message's files, by name and type only. */
    val files: List<V2InboxFileDto> = emptyList(),
)

/**
 * A file of a waiting message. The server sends its whole stored `data` (base64, about 0.7 MB for a photo) in the
 * inbox and in `session.inbox.enqueued`; it is deliberately not a field here, so decoding skips it and nothing keeps it.
 */
@Serializable
data class V2InboxFileDto(
    val name: String? = null,
    val mime: String? = null,
)

/** `POST /api/rpc/opencode.goal/get`'s body: `{"input": {"sessionID": …}}`. */
@Serializable
data class V2GoalGetRequestDto(
    val input: V2GoalSessionInputDto,
)

@Serializable
data class V2GoalSessionInputDto(
    @SerialName("sessionID") val sessionId: String,
)

/**
 * OpenCode's `/goal` state (`schema/src/goal.ts`, v2.2.5): [status] `active`, `paused` or `complete`; [reason] why it
 * last changed (absent on a new goal); [rounds] the continuations sent since it was set or last resumed (0 after
 * setting, 1 after resuming); [maxRounds] the limit per set or resume (50). Read with [fromJson], not a serializer.
 */
data class V2GoalStateDto(
    val id: String,
    val objective: String = "",
    val status: String = "",
    val reason: String? = null,
    val rounds: Int = 0,
    val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    /** Null when the state leaves it out. */
    val stalls: Int? = null,
    val updatedAt: Long? = null,
) {
    companion object {
        const val DEFAULT_MAX_ROUNDS = 50

        /**
         * A `Goal.State` read leniently: numbers may be fractional (`1.0`) or `null`, and a field of the wrong type
         * or left out takes its default. Null for no goal (`null`, or no value at all) and for a value that is not a
         * goal: not an object, or without a string `id` or `objective`. [onUnreadable] then gets why — never the
         * goal's content.
         */
        fun fromJson(
            element: JsonElement?,
            onUnreadable: (reason: String) -> Unit = {},
        ): V2GoalStateDto? {
            fun unreadable(reason: String): V2GoalStateDto? {
                onUnreadable(reason)
                return null
            }
            if (element == null || element is JsonNull) return null
            val state = element as? JsonObject ?: return unreadable("not an object")
            val id = state.string("id")?.takeIf { it.isNotBlank() } ?: return unreadable("no id")
            val objective = state.string("objective") ?: return unreadable("no objective")
            return V2GoalStateDto(
                id = id,
                objective = objective,
                status = state.string("status").orEmpty(),
                reason = state.string("reason"),
                rounds = state.number("rounds")?.toInt() ?: 0,
                maxRounds = state.number("maxRounds")?.toInt() ?: DEFAULT_MAX_ROUNDS,
                stalls = state.number("stalls")?.toInt(),
                updatedAt = state.number("updatedAt")?.toLong(),
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    }
}
