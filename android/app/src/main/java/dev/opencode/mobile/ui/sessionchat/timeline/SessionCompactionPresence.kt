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

    fun completedCompactionIds(messages: List<Message>): Set<String> = completedCompactions(messages).keys

    /** Completed compaction ids mapped to their summary (null when the item has none, e.g. failed). */
    fun completedCompactions(messages: List<Message>): Map<String, String?> =
        buildMap {
            messages.forEach { message ->
                message.parts.forEach { part ->
                    val compaction = part as? MessagePart.Compaction ?: return@forEach
                    if (!compaction.completed) return@forEach
                    put(compaction.id ?: message.id, compaction.summary)
                }
            }
        }

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
