package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole

/** Detects an in-flight compaction turn from live messages (remote or local). */
object SessionCompactionPresence {
    fun isActive(
        messages: List<Message>,
        isSessionBusy: Boolean,
    ): Boolean {
        if (!isSessionBusy) return false
        return activeTurn(messages).any(::isCompactionSignal)
    }

    private fun activeTurn(messages: List<Message>): List<Message> {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.User }
        return if (lastUserIndex < 0) messages else messages.drop(lastUserIndex)
    }

    private fun isCompactionSignal(message: Message): Boolean {
        if (message.parts.any { it is MessagePart.Compaction }) return true
        if (message.role != MessageRole.Assistant) return false
        return isCompactionMode(message.mode)
    }

    private fun isCompactionMode(raw: String?): Boolean = !raw.isNullOrEmpty() && raw.equals("compaction", ignoreCase = true)
}
