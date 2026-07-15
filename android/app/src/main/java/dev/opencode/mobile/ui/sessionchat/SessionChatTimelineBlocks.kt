package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus

/**
 * Session Chat presentation units inside one message timeline item.
 * Built from structured parts with OPE-49 hide rules and generic tool chrome.
 */
sealed class SessionChatTimelineBlock {
    data class Text(
        val text: String,
    ) : SessionChatTimelineBlock()

    data class ToolCard(
        val toolName: String,
        val status: ToolStatus,
        val title: String,
        val subtitle: String? = null,
        val errorText: String? = null,
    ) : SessionChatTimelineBlock()
}

object SessionChatTimelineBlocks {
    fun build(
        role: MessageRole,
        parts: List<MessagePart>,
    ): List<SessionChatTimelineBlock> {
        if (parts.isEmpty()) return emptyList()

        val allowToolCards = role == MessageRole.Assistant
        val blocks = mutableListOf<SessionChatTimelineBlock>()
        val pendingText = mutableListOf<String>()

        fun flushText() {
            if (pendingText.isEmpty()) return
            val merged = pendingText.joinToString("\n")
            pendingText.clear()
            if (merged.isNotEmpty()) {
                blocks += SessionChatTimelineBlock.Text(merged)
            }
        }

        for (part in parts) {
            when (part) {
                is MessagePart.Text -> {
                    val trimmed = part.text.trim()
                    if (trimmed.isNotEmpty()) {
                        pendingText += trimmed
                    }
                }
                is MessagePart.Tool -> {
                    if (!allowToolCards || !isVisibleTool(part)) continue
                    flushText()
                    blocks += toToolCard(part)
                }
            }
        }
        flushText()
        return blocks
    }

    private fun isVisibleTool(tool: MessagePart.Tool): Boolean {
        val name = tool.toolName.lowercase()
        if (name == "todowrite") return false
        if (name == "question" &&
            (tool.status == ToolStatus.Pending || tool.status == ToolStatus.Running)
        ) {
            return false
        }
        return true
    }

    private fun toToolCard(tool: MessagePart.Tool): SessionChatTimelineBlock.ToolCard {
        val subtitle =
            when (tool.status) {
                ToolStatus.Running -> tool.title?.takeIf { it.isNotEmpty() }
                else -> null
            }
        val errorText =
            when (tool.status) {
                ToolStatus.Error -> tool.error?.takeIf { it.isNotEmpty() }
                else -> null
            }
        return SessionChatTimelineBlock.ToolCard(
            toolName = tool.toolName,
            status = tool.status,
            title = "Called ${tool.toolName}",
            subtitle = subtitle,
            errorText = errorText,
        )
    }
}
