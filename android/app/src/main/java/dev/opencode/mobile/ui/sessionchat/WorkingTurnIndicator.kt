package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus

/**
 * In-chat Working/Thinking/Retrying/Compacting chrome for an active turn.
 * Reuses product labels from [EventDomainAdapter] (`Working` / `Retrying`),
 * plus [SessionCompactChrome.STATUS_LABEL] while compact is in flight.
 * Custom Working / Thinking footer lamps are off for now — TUI has no
 * transcript equivalent and they blink when parts settle. Retrying and
 * Compacting… still show. Hide this chrome while a question or permission
 * is waiting on the user — the dock is the wait state.
 */
object WorkingTurnIndicator {
    fun labelForActiveTurn(
        statusLabel: String?,
        messages: List<SessionChatMessageItem>,
        isCompacting: Boolean = false,
        isAwaitingUser: Boolean = false,
        @Suppress("UNUSED_PARAMETER") assistantTurnExists: Boolean = false,
    ): String? {
        if (isCompacting) return SessionCompactChrome.STATUS_LABEL
        if (isAwaitingUser) return null
        if (statusLabel == null) return null
        if (hasInProgressAssistantContentAfterLastUser(messages)) return null
        if (statusLabel == "Working") return null
        return statusLabel
    }

    fun assistantTurnExists(messages: List<Message>): Boolean {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.User }
        val after =
            if (lastUserIndex < 0) {
                messages
            } else {
                messages.drop(lastUserIndex + 1)
            }
        return after.any { message ->
            message.role == MessageRole.Assistant &&
                !message.mode.equals("compaction", ignoreCase = true)
        }
    }

    private fun assistantsAfterLastUser(messages: List<SessionChatMessageItem>): List<SessionChatMessageItem> {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.User }
        val candidates =
            if (lastUserIndex < 0) {
                messages
            } else {
                messages.drop(lastUserIndex + 1)
            }
        return candidates.filter { it.role == MessageRole.Assistant && !isCompactionSnapshot(it) }
    }

    private fun isCompactionSnapshot(message: SessionChatMessageItem): Boolean {
        if (message.metadataLabel.isEmpty()) return false
        val mode = message.metadataLabel.substringBefore(" · ")
        return mode.equals("compaction", ignoreCase = true)
    }

    private fun hasInProgressAssistantContentAfterLastUser(messages: List<SessionChatMessageItem>): Boolean =
        assistantsAfterLastUser(messages).any(::hasInProgressContent)

    private fun hasInProgressContent(message: SessionChatMessageItem): Boolean {
        if (message.isStreaming) return true
        return message.blocks.any { block ->
            when (block) {
                is SessionChatTimelineBlock.Reasoning -> block.isStreaming
                is SessionChatTimelineBlock.ToolCard -> block.status.isInProgress
                is SessionChatTimelineBlock.ToolGroup ->
                    block.status.isInProgress || block.members.any { it.status.isInProgress }
                is SessionChatTimelineBlock.Text,
                is SessionChatTimelineBlock.Attachment,
                is SessionChatTimelineBlock.ChildSessionRow,
                -> false
            }
        }
    }
}

private val ToolStatus.isInProgress: Boolean
    get() = this == ToolStatus.Pending || this == ToolStatus.Running
