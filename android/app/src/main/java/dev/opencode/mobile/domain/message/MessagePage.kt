package dev.opencode.mobile.domain.message

/** Bounded history page. Messages are chronological (oldest first). */
data class MessagePage(
    val messages: List<Message>,
    val olderCursor: String?,
    val hasOlder: Boolean,
)

data class MessageHistoryQuery(
    val limit: Int,
    val olderCursor: String? = null,
) {
    val isTail: Boolean get() = olderCursor == null

    companion object {
        fun tail(limit: Int = MessageHistoryWindow.TAIL_LIMIT) = MessageHistoryQuery(limit = limit)

        fun older(
            cursor: String,
            limit: Int = MessageHistoryWindow.OLDER_LIMIT,
        ) = MessageHistoryQuery(limit = limit, olderCursor = cursor)
    }
}

object MessageHistoryWindow {
    const val TAIL_LIMIT = 20
    const val OLDER_LIMIT = 200
    const val SPLIT_TURN_MAX_PAGES = 3
}

fun MessagePage.plusOlder(older: MessagePage): MessagePage {
    val seen = messages.map { it.id }.toHashSet()
    val prepended = older.messages.filter { seen.add(it.id) }
    return MessagePage(
        messages = prepended + messages,
        olderCursor = older.olderCursor,
        hasOlder = older.hasOlder,
    )
}

data class OlderLoadResult(
    val messages: List<Message>,
    val hasOlder: Boolean,
    val addedCount: Int,
)

fun beginsMidTurn(messages: List<Message>): Boolean {
    val oldest = messages.firstOrNull() ?: return false
    return oldest.role != MessageRole.User && oldest.role != MessageRole.Command
}
