package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.PromptSendProgress

/**
 * Last-timeline-row Session Running chrome. Show iff this session's
 * status is not idle, or a large prompt body is still on the wire.
 * Optimistic busy on send is the same status. Hide when a permission
 * or question dock is waiting on the user. When the last painted row
 * is Thinking, keep this label so the timeline can collapse the reserved
 * Working row in place — do not drop the list identity.
 * Do not hide for settled Thought, tools, or streaming text.
 */
object WorkingTurnIndicator {
    fun lastIsThinking(messages: List<SessionChatMessageItem>): Boolean {
        val last = messages.lastOrNull()?.blocks?.lastOrNull() as? SessionChatTimelineBlock.Reasoning
        return last?.isStreaming == true
    }

    fun shouldCollapseReservedRow(
        lastIsThinking: Boolean,
        label: String?,
    ): Boolean = lastIsThinking && label != null

    fun labelForActiveTurn(
        statusLabel: String?,
        isCompacting: Boolean = false,
        isAwaitingUser: Boolean = false,
        sendProgressPercent: Int? = null,
    ): String? {
        if (isAwaitingUser) return null
        if (isCompacting) return SessionCompactChrome.STATUS_LABEL
        if (sendProgressPercent != null) {
            return PromptSendProgress.label(sendProgressPercent)
        }
        return statusLabel
    }
}
