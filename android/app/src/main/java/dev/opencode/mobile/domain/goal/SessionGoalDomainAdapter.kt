package dev.opencode.mobile.domain.goal

import dev.opencode.mobile.data.remote.HttpBodyResponseException
import dev.opencode.mobile.data.remote.dto.V2GoalStateDto
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.json.serverPayloadJson
import dev.opencode.mobile.domain.session.ServerErrorMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A goal change for one session: [goal] null means it was cleared. */
data class SessionGoalChange(
    val sessionId: String,
    val goal: SessionGoal?,
)

/** Wire `Goal.State` ([V2GoalStateDto]: RPC output and `rpc.opencode.goal.changed` data) to [SessionGoal]. */
object SessionGoalDomainAdapter {
    /** v2 `/api/event` type of a goal change: `rpc.<rpcID>.<event>`. */
    const val CHANGED_EVENT = "rpc.opencode.goal.changed"

    private val json = serverPayloadJson()

    /** A `Goal.State` object, or null for `null` or a value that is not one. */
    fun goal(element: JsonElement?): SessionGoal? {
        val obj = element as? JsonObject ?: return null
        return fromState(runCatching { json.decodeFromJsonElement(V2GoalStateDto.serializer(), obj) }.getOrNull())
    }

    /** The RPC's state, or null for no goal, a blank id or an unknown status. */
    fun fromState(state: V2GoalStateDto?): SessionGoal? {
        if (state == null) return null
        val id = state.id.takeIf { it.isNotBlank() } ?: return null
        val status =
            when (state.status) {
                "active" -> SessionGoalStatus.Active
                "paused" -> SessionGoalStatus.Paused
                "complete" -> SessionGoalStatus.Complete
                else -> return null
            }
        return SessionGoal(
            id = id,
            objective = state.objective,
            status = status,
            reason = state.reason?.takeIf { it.isNotBlank() },
            rounds = state.rounds,
            maxRounds = state.maxRounds.takeIf { it > 0 } ?: DEFAULT_MAX_ROUNDS,
            stalls = state.stalls,
            updatedAtEpochMs = state.updatedAt ?: 0L,
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

    private const val DEFAULT_MAX_ROUNDS = 50
}
