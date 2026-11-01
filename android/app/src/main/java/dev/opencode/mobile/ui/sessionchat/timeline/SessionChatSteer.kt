package dev.opencode.mobile.ui.sessionchat

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
