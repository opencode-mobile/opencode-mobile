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
    const val IDLE_HOLD_MS = 1_000L

    data class Chrome(
        val label: String?,
        val collapsed: Boolean,
        val fading: Boolean,
    ) {
        companion object {
            val Unmounted = Chrome(label = null, collapsed = false, fading = false)
        }
    }

    data class Hold(
        val label: String,
        val startedAtMs: Long,
        val fromCollapsed: Boolean,
    )

    data class Resolution(
        val chrome: Chrome,
        val hold: Hold?,
        val wakeAfterMs: Long? = null,
    )

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

    fun resolveFooter(
        activeLabel: String?,
        lastIsThinking: Boolean,
        hideImmediately: Boolean,
        previous: Chrome,
        hold: Hold?,
        nowMs: Long,
    ): Resolution {
        if (hideImmediately) {
            return Resolution(chrome = Chrome.Unmounted, hold = null)
        }
        if (activeLabel != null) {
            return Resolution(
                chrome =
                    Chrome(
                        label = activeLabel,
                        collapsed = shouldCollapseReservedRow(lastIsThinking, activeLabel),
                        fading = false,
                    ),
                hold = null,
            )
        }
        if (previous.label == null && hold == null) {
            return Resolution(chrome = Chrome.Unmounted, hold = null)
        }
        val nextHold =
            hold ?: Hold(
                label = checkNotNull(previous.label),
                startedAtMs = nowMs,
                fromCollapsed = previous.collapsed,
            )
        if (nextHold.fromCollapsed) {
            return Resolution(
                chrome =
                    Chrome(
                        label = nextHold.label,
                        collapsed = true,
                        fading = false,
                    ),
                hold = nextHold,
            )
        }
        val remainingMs = IDLE_HOLD_MS - (nowMs - nextHold.startedAtMs)
        return if (remainingMs > 0) {
            Resolution(
                chrome =
                    Chrome(
                        label = nextHold.label,
                        collapsed = false,
                        fading = true,
                    ),
                hold = nextHold,
                wakeAfterMs = remainingMs,
            )
        } else {
            Resolution(
                chrome =
                    Chrome(
                        label = nextHold.label,
                        collapsed = true,
                        fading = false,
                    ),
                hold = nextHold,
            )
        }
    }
}
