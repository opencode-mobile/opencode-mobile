package dev.opencode.mobile.domain.event

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class OpencodeEvent(
    val type: String,
    val sessionId: String? = null,
    val rawProperties: JsonObject? = null,
    val id: String? = null,
) {
    val triggersMessageRefresh: Boolean
        get() =
            type in MESSAGE_REFRESH_TYPES

    val triggersHomeRefresh: Boolean
        get() =
            type in HOME_REFRESH_TYPES

    val triggersStatusRefresh: Boolean
        get() =
            type in STATUS_REFRESH_TYPES

    companion object {
        val MESSAGE_REFRESH_TYPES =
            setOf(
                "message.updated",
                "message.part.updated",
                "message.part.delta",
                "message.part.removed",
                "message.removed",
                "session.status",
                "session.idle",
                "session.error",
            )

        val HOME_REFRESH_TYPES =
            setOf(
                "session.created",
                "session.updated",
                "session.deleted",
                // v2 archive state changes; the list refetch drops or restores the session.
                "session.archived",
                "session.unarchived",
            )

        val STATUS_REFRESH_TYPES =
            setOf(
                "session.status",
                "session.idle",
            )
    }
}

fun OpencodeEvent.namedProjectId(): String? {
    val properties = rawProperties ?: return null
    fun string(key: String) = (properties[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    return string("projectID") ?: string("projectId")
}

/** A session now runs in [directory] (v2 `session.moved`). */
data class SessionMovedUpdate(
    val sessionId: String,
    val directory: String,
)

/** The move this event reports, or null for any other event. */
fun OpencodeEvent.sessionMoved(): SessionMovedUpdate? {
    if (type != EventDomainAdapter.SESSION_MOVED_EVENT) return null
    val properties = rawProperties ?: return null
    val sessionId =
        sessionId
            ?: (properties["sessionID"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
    val directory =
        ((properties["location"] as? JsonObject)?.get("directory") as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return null
    return SessionMovedUpdate(sessionId = sessionId, directory = directory)
}
