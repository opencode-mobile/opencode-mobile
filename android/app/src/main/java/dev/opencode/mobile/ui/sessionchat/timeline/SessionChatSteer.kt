package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole

/**
 * A user prompt sent with steer delivery during a run, as the timeline shows it.
 * Queued prompts never get one: they stay out of the timeline.
 */
data class SessionChatSteer(
    /** Waiting for the next step; the server has not delivered it yet. */
    val isPending: Boolean,
    /** First of a run of consecutive steers, so it draws the shared label. */
    val showsLabel: Boolean,
)

/**
 * Whether the current busy period has produced assistant output. Between two steps the
 * newest assistant message is finished, so only this tells a steer waiting for the next
 * step from an idle prompt, which v2 also enqueues as a steer but delivers before any output.
 */
class SteerRunTracker {
    private var hasResolvedStatus = false
    private var isBusy = false
    private var baselineAssistantId: String? = null

    fun update(
        isBusy: Boolean,
        isResolved: Boolean,
        messages: List<Message>,
    ) {
        if (!isResolved) return
        if (isBusy && !this.isBusy) {
            // Opening a session mid-run: everything loaded may belong to this run.
            baselineAssistantId = if (hasResolvedStatus) newestAssistantId(messages) else null
        }
        hasResolvedStatus = true
        this.isBusy = isBusy
    }

    fun hasOutput(messages: List<Message>): Boolean {
        if (!isBusy) return false
        val newest = newestAssistantId(messages) ?: return false
        return newest != baselineAssistantId
    }

    private fun newestAssistantId(messages: List<Message>): String? = messages.lastOrNull { it.role == MessageRole.Assistant }?.id
}
