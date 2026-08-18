package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus

/**
 * In-chat Working/Retrying/Compacting chrome for an active turn.
 * Reuses product labels from [EventDomainAdapter] (`Working` / `Retrying`),
 * plus [SessionCompactChrome.STATUS_LABEL] while compact is in flight.
 */
object WorkingTurnIndicator {
    fun labelForActiveTurn(
        statusLabel: String?,
        messages: List<SessionChatMessageItem>,
        isCompacting: Boolean = false,
    ): String? {
        if (isCompacting) return SessionCompactChrome.STATUS_LABEL
        if (statusLabel == null) return null
        if (hasInProgressAssistantContentAfterLastUser(messages)) return null
        return statusLabel
    }

    private fun hasInProgressAssistantContentAfterLastUser(messages: List<SessionChatMessageItem>): Boolean {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.User }
        val candidates =
            if (lastUserIndex < 0) {
                messages
            } else {
                messages.drop(lastUserIndex + 1)
            }
        return candidates.any { it.role == MessageRole.Assistant && hasInProgressContent(it) }
    }

    private fun hasInProgressContent(message: SessionChatMessageItem): Boolean {
        if (message.isStreaming) return true
        if (message.text.isNotBlank()) return true
        return message.blocks.any { block ->
            when (block) {
                is SessionChatTimelineBlock.Text -> block.text.isNotBlank()
                is SessionChatTimelineBlock.Reasoning -> block.isStreaming || block.text.isNotBlank()
                is SessionChatTimelineBlock.Attachment,
                is SessionChatTimelineBlock.ChildSessionRow,
                -> true
                is SessionChatTimelineBlock.ToolCard -> block.status.isInProgress
                is SessionChatTimelineBlock.ToolGroup ->
                    block.status.isInProgress || block.members.any { it.status.isInProgress }
            }
        }
    }
}

private val ToolStatus.isInProgress: Boolean
    get() = this == ToolStatus.Pending || this == ToolStatus.Running
