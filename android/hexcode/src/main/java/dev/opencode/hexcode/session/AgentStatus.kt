package dev.opencode.hexcode.session

/** What home's top bar says about the agent (app-plan.md, Today's session on screen). */
sealed interface AgentStatus {
    /**
     * Today's session is being opened, or its state hasn't been read back yet (app-plan.md, Home › Cold start): the
     * Reconnecting arc in `text3`, not amber.
     */
    data object Connecting : AgentStatus

    /** The event stream is down. */
    data object Reconnecting : AgentStatus

    /** A question or an approval is waiting for you. */
    data object NeedsInput : AgentStatus

    /** The session is busy; the turn started at [since] ([System.currentTimeMillis] time). */
    data class Working(
        val since: Long,
    ) : AgentStatus {
        /** The turn's clock at [now]: "0:07", "4:12", "72:03". */
        fun clock(now: Long): String {
            val seconds = ((now - since).coerceAtLeast(0) / 1000).toInt()
            return "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }

    data object Idle : AgentStatus

    companion object {
        /**
         * The first that applies: the stream down, then pending input, then busy, then idle. [busySince] is when
         * the current turn started, or null when the session is idle.
         */
        fun of(
            streamUp: Boolean,
            pendingInput: Boolean,
            busySince: Long?,
        ): AgentStatus =
            when {
                !streamUp -> Reconnecting
                pendingInput -> NeedsInput
                busySince != null -> Working(busySince)
                else -> Idle
            }
    }
}
