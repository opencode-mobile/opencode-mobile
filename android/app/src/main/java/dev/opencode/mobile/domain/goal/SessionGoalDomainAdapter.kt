package dev.opencode.mobile.domain.goal

import dev.opencode.mobile.data.remote.HttpBodyResponseException
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.session.ServerErrorMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** A goal change for one session: [goal] null means it was cleared. */
data class SessionGoalChange(
    val sessionId: String,
    val goal: SessionGoal?,
)

/** Wire `Goal.State` (RPC output and `rpc.opencode.goal.changed` data) to [SessionGoal]. */
object SessionGoalDomainAdapter {
    /** v2 `/api/event` type of a goal change: `rpc.<rpcID>.<event>`. */
    const val CHANGED_EVENT = "rpc.opencode.goal.changed"

    /** A `Goal.State` object, or null for `null` or a value that is not one. */
    fun goal(element: JsonElement?): SessionGoal? {
        val json = element as? JsonObject ?: return null
        val id = json.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val objective = json.string("objective") ?: return null
        val status =
            when (json.string("status")) {
                "active" -> SessionGoalStatus.Active
                "paused" -> SessionGoalStatus.Paused
                "complete" -> SessionGoalStatus.Complete
                else -> return null
            }
        return SessionGoal(
            id = id,
            objective = objective,
            status = status,
            reason = json.string("reason")?.takeIf { it.isNotBlank() },
            rounds = json.number("rounds")?.toInt() ?: 0,
            maxRounds = json.number("maxRounds")?.toInt()?.takeIf { it > 0 } ?: DEFAULT_MAX_ROUNDS,
            stalls = json.number("stalls")?.toInt(),
            updatedAtEpochMs = json.number("updatedAt")?.toLong() ?: 0L,
        )
    }

    /** The change a live event carries, or null for any other event. */
    fun change(event: OpencodeEvent): SessionGoalChange? {
        if (event.type != CHANGED_EVENT) return null
        val properties = event.rawProperties ?: return null
        val sessionId =
            properties.string("sessionID")?.takeIf { it.isNotBlank() }
                ?: event.sessionId
                ?: return null
        return SessionGoalChange(sessionId = sessionId, goal = goal(properties["goal"]))
    }

    /** The command's own error ("No active goal to pause."), when the server sent one. */
    fun errorMessage(error: Throwable): String? {
        val body = (error as? HttpBodyResponseException)?.bodyText ?: return null
        ServerErrorMessage.userFacing(body)?.let { return it }
        val text = body.trim()
        return text.takeIf { it.isNotEmpty() && !it.startsWith("{") && !it.contains('\n') && it.length <= 200 }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private const val DEFAULT_MAX_ROUNDS = 50
}
