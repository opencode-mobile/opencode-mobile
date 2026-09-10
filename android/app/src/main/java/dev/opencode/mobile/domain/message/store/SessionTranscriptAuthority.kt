package dev.opencode.mobile.domain.message

object SessionTranscriptAuthority {
    fun lastAssistantNeedsAuthority(messages: List<Message>): Boolean {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.User }
        val afterLastUser = if (lastUserIndex < 0) messages else messages.drop(lastUserIndex + 1)
        val lastAssistant =
            afterLastUser.lastOrNull { it.role == MessageRole.Assistant } ?: return false
        return !hasVisibleContent(lastAssistant)
    }

    private fun hasVisibleContent(message: Message): Boolean =
        message.parts.any { part ->
            when (part) {
                is MessagePart.Text ->
                    part.isDisplayable && (part.isStreaming || part.text.isNotBlank())
                is MessagePart.Reasoning -> ThoughtPartChrome.isVisible(part)
                is MessagePart.Tool -> ToolPartIdentity.isVisibleOnTimeline(part)
                is MessagePart.File -> true
                is MessagePart.Compaction -> false
            }
        }
}
