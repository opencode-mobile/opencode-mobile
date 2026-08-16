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

    fun completedCompactionIds(messages: List<Message>): Set<String> =
        messages
            .flatMap { message ->
                message.parts.mapNotNull { part ->
                    val compaction = part as? MessagePart.Compaction ?: return@mapNotNull null
                    if (!compaction.completed) return@mapNotNull null
                    compaction.id ?: message.id
                }
            }.toSet()

    private fun activeTurn(messages: List<Message>): List<Message> {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.User }
        return if (lastUserIndex < 0) messages else messages.drop(lastUserIndex)
    }

    private fun isCompactionSignal(message: Message): Boolean {
        val compactionParts = message.parts.filterIsInstance<MessagePart.Compaction>()
        if (compactionParts.any { !it.completed }) return true
        if (compactionParts.any { it.completed }) return false
        if (message.role != MessageRole.Assistant) return false
        return isCompactionMode(message.mode)
    }

    private fun isCompactionMode(raw: String?): Boolean = !raw.isNullOrEmpty() && raw.equals("compaction", ignoreCase = true)
}
