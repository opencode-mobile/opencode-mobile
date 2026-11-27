package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ThoughtPartChrome
import dev.opencode.mobile.domain.message.ToolPartIdentity
import dev.opencode.mobile.domain.text.UiTextLookup
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailTarget
import dev.opencode.mobile.ui.sessionchat.view.thoughtDetailTarget

/**
 * The thought the thought sheet shows. Resolved from the loaded messages on every publish, so
 * the sheet follows the text while it streams and settles in place when it ends (#1118).
 */
data class SessionChatThoughtSheetState(
    val partId: String,
    /** Display text: `[REDACTED]` removed and trimmed. */
    val text: String,
    val isStreaming: Boolean,
    /** How long a settled thought took; null while streaming or when the server sent no times. */
    val durationMs: Long?,
)

/** Rules for the turn's live thought: which thought is streaming now, and what the sheet shows. */
object SessionChatLiveThought {
    /**
     * The id of the thought streaming at the end of the current turn, or null. Only a thought
     * that is the turn's newest visible part counts: once a tool, text or file follows it, the
     * turn has moved on even if the server has not closed the thought yet.
     */
    fun streamingPartId(messages: List<Message>): String? {
        for (message in currentTurnAssistants(messages).asReversed()) {
            for (part in message.parts.asReversed()) {
                when (part) {
                    is MessagePart.Reasoning -> {
                        if (!ThoughtPartChrome.isVisible(part)) continue
                        return if (part.isStreaming) part.id else null
                    }
                    is MessagePart.Text ->
                        if (part.isDisplayable && (part.isStreaming || part.text.isNotEmpty())) return null
                    is MessagePart.Tool ->
                        if (ToolPartIdentity.isVisibleOnTimeline(part)) return null
                    is MessagePart.File -> return null
                    is MessagePart.Compaction,
                    is MessagePart.Notice,
                    -> Unit
                }
            }
        }
        return null
    }

    /**
     * The sheet for the thought [partId], wherever it is in the loaded messages; null once it is
     * gone (reverted, another session) or blank.
     */
    fun sheet(
        partId: String,
        messages: List<Message>,
    ): SessionChatThoughtSheetState? {
        for (message in messages.asReversed()) {
            val part =
                message.parts.firstOrNull { it is MessagePart.Reasoning && it.id == partId } as? MessagePart.Reasoning
                    ?: continue
            val text = ThoughtPartChrome.displayText(part)
            if (text.isEmpty()) return null
            return SessionChatThoughtSheetState(
                partId = partId,
                text = text,
                isStreaming = part.isStreaming,
                durationMs = if (part.isStreaming) null else durationMs(part.timeStartEpochMs, part.timeEndEpochMs),
            )
        }
        return null
    }

    /**
     * Assistant replies after the newest sent prompt. A prompt still waiting for pickup has not
     * started a turn, so the reply before it is still the current one.
     */
    private fun currentTurnAssistants(messages: List<Message>): List<Message> {
        val lastPrompt = messages.indexOfLast { it.role == MessageRole.User && !it.isWaitingForPickup }
        return messages.drop(lastPrompt + 1).filter { it.role == MessageRole.Assistant }
    }

    private fun durationMs(
        start: Long?,
        end: Long?,
    ): Long? {
        if (start == null || end == null || end < start) return null
        return end - start
    }
}

/**
 * The standard thought sheet. Its subline reads [thinkingLabel] ("Thinking…") while the thought
 * streams and the duration once it settles.
 */
internal fun SessionChatThoughtSheetState.detailTarget(
    thinkingLabel: String,
    lookup: UiTextLookup,
): SessionChatToolDetailTarget =
    thoughtDetailTarget(
        text = text,
        durationLabel = if (isStreaming) thinkingLabel else SessionChatTimelineBlocks.thoughtDurationLabel(durationMs, lookup),
    )
