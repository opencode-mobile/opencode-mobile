package dev.opencode.mobile.ui.sessionchat

/**
 * Last-timeline-row Session Running chrome. Show iff this session's
 * status is not idle. Optimistic busy on send is the same status. Do not
 * inspect messages, streaming, tools, or thought rows. Hide only when a
 * permission or question dock is waiting on the user.
 */
object WorkingTurnIndicator {
    fun labelForActiveTurn(
        statusLabel: String?,
        isCompacting: Boolean = false,
        isAwaitingUser: Boolean = false,
    ): String? {
        if (isAwaitingUser) return null
        if (isCompacting) return SessionCompactChrome.STATUS_LABEL
        return statusLabel
    }
}
