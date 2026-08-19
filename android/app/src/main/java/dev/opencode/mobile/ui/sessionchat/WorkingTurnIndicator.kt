package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus

/**
 * In-chat Working/Thinking/Retrying/Compacting chrome for an active turn.
 * Reuses product labels from [EventDomainAdapter] (`Working` / `Retrying`),
 * plus [SessionCompactChrome.STATUS_LABEL] while compact is in flight.
 * After settled assistant content, a still-busy turn shows [THINKING_LABEL]
 * unless Retrying. Working is only for “no assistant turn yet.” An empty
 * or still-hidden assistant slot is the wait — do not paint Working over
 * it or it blinks off as soon as the first part lands.
 * In-flight questions are hidden from the timeline and shown in the dock —
 * that dock is the wait state, so do not add a second Thinking line.
 */
object WorkingTurnIndicator {
    const val THINKING_LABEL = "Thinking"

    fun labelForActiveTurn(
        statusLabel: String?,
        messages: List<SessionChatMessageItem>,
        isCompacting: Boolean = false,
        isAwaitingUser: Boolean = false,
        assistantTurnExists: Boolean = false,
    ): String? {
        if (isCompacting) return SessionCompactChrome.STATUS_LABEL
        if (isAwaitingUser) return null
        if (statusLabel == null) return null
        if (hasInProgressAssistantContentAfterLastUser(messages)) return null
        if (statusLabel == "Working" && hasSettledAssistantContentAfterLastUser(messages)) {
            return THINKING_LABEL
        }
        if (statusLabel == "Working" &&
            (assistantTurnExists || assistantsAfterLastUser(messages).isNotEmpty())
        ) {
            return null
        }
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

    private fun hasSettledAssistantContentAfterLastUser(messages: List<SessionChatMessageItem>): Boolean =
        assistantsAfterLastUser(messages).any(::hasSettledContent)

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

    private fun hasSettledContent(message: SessionChatMessageItem): Boolean {
        if (message.text.isNotBlank()) return true
        return message.blocks.any { block ->
            when (block) {
                is SessionChatTimelineBlock.Text -> block.text.isNotBlank()
                is SessionChatTimelineBlock.Reasoning -> !block.isStreaming && block.text.isNotBlank()
                is SessionChatTimelineBlock.Attachment,
                is SessionChatTimelineBlock.ChildSessionRow,
                -> true
                is SessionChatTimelineBlock.ToolCard -> !block.status.isInProgress
                is SessionChatTimelineBlock.ToolGroup ->
                    !block.status.isInProgress && block.members.none { it.status.isInProgress }
            }
        }
    }
}

private val ToolStatus.isInProgress: Boolean
    get() = this == ToolStatus.Pending || this == ToolStatus.Running
