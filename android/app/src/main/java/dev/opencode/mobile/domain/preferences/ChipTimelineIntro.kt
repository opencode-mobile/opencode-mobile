package dev.opencode.mobile.domain.preferences

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolPartIdentity

/**
 * One-time Chip timeline intro sheet (OPE-270): when Session Chat offers it and
 * what an answer stores. Storage lives in [SessionChatDisplayPreferences].
 */
object ChipTimelineIntro {
    /** A finished turn must have used at least this many tool calls. */
    const val MIN_TOOL_CALLS = 8

    /** Later hides the sheet for this long; a second Later ends the intro. */
    const val SNOOZE_MILLIS = 3L * 24 * 60 * 60 * 1000

    /** Pause after the turn finishes so the user sees the result first. */
    const val SETTLE_DELAY_MILLIS = 1_500L

    fun shouldShow(
        chipTimelineAvailable: Boolean,
        chipTimelineOn: Boolean,
        introDone: Boolean,
        snoozedUntilMillis: Long?,
        nowMillis: Long,
        turnToolCalls: Int,
    ): Boolean =
        chipTimelineAvailable &&
            !chipTimelineOn &&
            !introDone &&
            (snoozedUntilMillis == null || snoozedUntilMillis <= nowMillis) &&
            turnToolCalls >= MIN_TOOL_CALLS

    /** Snooze end for a Later answer, or null when this Later ends the intro. */
    fun laterSnoozeUntil(
        snoozedUntilMillis: Long?,
        nowMillis: Long,
    ): Long? = if (snoozedUntilMillis == null) nowMillis + SNOOZE_MILLIS else null

    /** Visible tool calls in the assistant messages after the last user message. */
    fun lastTurnToolCalls(messages: List<Message>): Int {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.User }
        return messages
            .drop(lastUserIndex + 1)
            .filter { it.role == MessageRole.Assistant }
            .sumOf { message ->
                message.parts.count { it is MessagePart.Tool && ToolPartIdentity.isVisibleOnTimeline(it) }
            }
    }
}
