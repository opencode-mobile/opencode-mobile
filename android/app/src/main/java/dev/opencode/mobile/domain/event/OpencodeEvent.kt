package dev.opencode.mobile.domain.event

import kotlinx.serialization.json.JsonObject

data class OpencodeEvent(
    val type: String,
    val sessionId: String? = null,
    val rawProperties: JsonObject? = null,
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
            )

        val STATUS_REFRESH_TYPES =
            setOf(
                "session.status",
                "session.idle",
            )
    }
}
