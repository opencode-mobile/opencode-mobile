package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.PromptSendProgress

/**
 * Last-timeline-row Session Running chrome. Show iff this session's
 * status is not idle, or a large prompt body is still on the wire.
 * Optimistic busy on send is the same status. Hide when a permission
 * or question dock is waiting on the user. Streaming Thinking is the
 * same reserved footer — change the label in place. Do not collapse
 * under a thought row or the footer goes blank between labels.
 * After busy ends, show Idle for 5s, then collapse. A new send snaps back.
 */
object WorkingTurnIndicator {
    const val IDLE_LABEL = "Idle"
    const val THINKING_LABEL = "Thinking"
    const val IDLE_HOLD_MS = 5_000L

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
        val startedAtMs: Long,
    )

    data class Resolution(
        val chrome: Chrome,
        val hold: Hold?,
        val wakeAfterMs: Long? = null,
    )

    fun lastIsThinking(messages: List<SessionChatMessageItem>): Boolean =
        messages.any { message ->
            message.blocks.any { block ->
                block is SessionChatTimelineBlock.Reasoning && block.isStreaming
            }
        }

    fun isStreamingThinking(messages: List<Message>): Boolean =
        messages.any { message ->
            message.parts.any { part ->
                part is MessagePart.Reasoning && part.isStreaming
            }
        }

    @Suppress("UNUSED_PARAMETER")
    fun shouldCollapseReservedRow(
        lastIsThinking: Boolean,
        label: String?,
    ): Boolean = false

    fun showsIdleMark(label: String): Boolean = label == IDLE_LABEL

    fun labelForActiveTurn(
        statusLabel: String?,
        isCompacting: Boolean = false,
        isAwaitingUser: Boolean = false,
        sendProgressPercent: Int? = null,
        isThinking: Boolean = false,
    ): String? {
        if (isAwaitingUser) return null
        if (isCompacting) return SessionCompactChrome.STATUS_LABEL
        if (sendProgressPercent != null) {
            return PromptSendProgress.label(sendProgressPercent)
        }
        if (isThinking) return THINKING_LABEL
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
        val nextHold = hold ?: Hold(startedAtMs = nowMs)
        val remainingMs = IDLE_HOLD_MS - (nowMs - nextHold.startedAtMs)
        return if (remainingMs > 0) {
            Resolution(
                chrome =
                    Chrome(
                        label = IDLE_LABEL,
                        collapsed = false,
                        fading = false,
                    ),
                hold = nextHold,
                wakeAfterMs = remainingMs,
            )
        } else {
            Resolution(
                chrome =
                    Chrome(
                        label = IDLE_LABEL,
                        collapsed = true,
                        fading = false,
                    ),
                hold = nextHold,
            )
        }
    }
}
