package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
import dev.opencode.mobile.data.remote.dto.V2SessionDto
import dev.opencode.mobile.data.remote.dto.V2SwitchModelRequestDto
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import kotlin.coroutines.cancellation.CancellationException

/** The `/agent` and `/model` posts a v2 send still needs before its prompt or command. */
internal data class V2SelectionSwitches(
    val agent: String?,
    val model: V2SwitchModelRequestDto?,
)

/**
 * Decides which of the composer's picks a v2 send has to apply. The server appends an `agent-switched`
 * message for every `/agent` post, even an unchanged one, so only a pick that differs from the session's
 * current agent is posted. The model gets the same check (the server already ignores an unchanged one,
 * default variant included); this saves the request. With no [current] session record every pick is
 * posted, as before.
 */
internal fun v2SelectionSwitches(
    current: V2SessionDto?,
    agent: String?,
    model: V2SwitchModelRequestDto?,
): V2SelectionSwitches {
    val pickedAgent = agent?.takeIf { it.isNotBlank() }
    return V2SelectionSwitches(
        agent = pickedAgent?.takeUnless { current != null && current.agent == it },
        model = model?.takeUnless { current != null && current.model.sameModelAs(it.model) },
    )
}

private fun V2ModelRefDto?.sameModelAs(other: V2ModelRefDto): Boolean =
    this != null &&
        providerId == other.providerId &&
        id == other.id &&
        (variant ?: DEFAULT_VARIANT) == (other.variant ?: DEFAULT_VARIANT)

private const val DEFAULT_VARIANT = "default"

/**
 * Applies a v2 send's agent and model before its prompt or command, skipping what the session already
 * uses. The session is read fresh each time, so a switch another client made since is respected.
 */
internal class V2SessionSelectionApplier(
    private val messagesApi: MessagesApi,
    private val sessionsApi: SessionsApi,
) {
    /** A failed switch throws, so the send fails instead of running with the old agent or model. */
    suspend fun apply(
        sessionId: String,
        agent: String?,
        model: V2SwitchModelRequestDto?,
        directory: String?,
    ) {
        if (agent.isNullOrBlank() && model == null) return
        val switches = v2SelectionSwitches(currentSession(sessionId), agent, model)
        switches.agent?.let { messagesApi.switchSessionAgent(sessionId, it, directory) }
        switches.model?.let { messagesApi.switchSessionModel(sessionId, it, directory) }
    }

    /** Null when the read fails: the picks are then all posted, which is safe, only noisier. */
    private suspend fun currentSession(sessionId: String): V2SessionDto? =
        try {
            sessionsApi.getSessionV2(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DiagnosticLog.warning(DiagnosticLogArea.Chat, "session read before send failed session=$sessionId", error)
            null
        }
}
