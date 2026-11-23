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
    private var settledUserId: String? = null
    private var settledAssistantId: String? = null

    fun update(
        isBusy: Boolean,
        isResolved: Boolean,
        messages: List<Message>,
    ) {
        if (!isResolved) return
        if (isBusy && !this.isBusy) {
            // Opening a session mid-run: everything loaded may belong to this run.
            baselineAssistantId = if (hasResolvedStatus) runBaseline(messages) else null
        } else if (!isBusy) {
            settle(messages)
        }
        hasResolvedStatus = true
        this.isBusy = isBusy
    }

    fun hasOutput(messages: List<Message>): Boolean {
        if (!isBusy) return false
        val newest = newestAssistantId(messages) ?: return false
        return newest != baselineAssistantId
    }

    /**
     * Follows late output of the finished run while idle. A newer user prompt means another
     * run has started and its busy status is still on the way, so its output is not settled.
     */
    private fun settle(messages: List<Message>) {
        val newestUserId = messages.lastOrNull { it.role == MessageRole.User }?.id
        val stayedIdle = hasResolvedStatus && !isBusy
        if (stayedIdle && settledUserId != null && newestUserId != settledUserId) return
        settledUserId = newestUserId
        settledAssistantId = newestAssistantId(messages)
    }

    /** A prompt waiting after the newest assistant message woke this run, so nothing before it is output. */
    private fun runBaseline(messages: List<Message>): String? {
        val newestIndex = messages.indexOfLast { it.role == MessageRole.Assistant }
        val promptWokeRun =
            messages.drop(newestIndex + 1).any { it.role == MessageRole.User && it.isWaitingForPickup }
        return if (promptWokeRun) newestAssistantId(messages) else settledAssistantId
    }

    private fun newestAssistantId(messages: List<Message>): String? = messages.lastOrNull { it.role == MessageRole.Assistant }?.id
}
