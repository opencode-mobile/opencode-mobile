package dev.opencode.mobile.domain.message

object MessageHistoryPages {
    suspend fun fetchTail(listPage: suspend (MessageHistoryQuery) -> MessagePage): MessagePage {
        var page = listPage(MessageHistoryQuery.tail())
        var extra = 0
        while (
            beginsMidTurn(page.messages) &&
            page.hasOlder &&
            !page.olderCursor.isNullOrBlank() &&
            extra < MessageHistoryWindow.SPLIT_TURN_MAX_PAGES
        ) {
            val older =
                listPage(
                    MessageHistoryQuery.older(
                        cursor = page.olderCursor!!,
                        limit = MessageHistoryWindow.TAIL_LIMIT,
                    ),
                )
            page = page.plusOlder(older)
            extra += 1
            if (older.messages.isEmpty()) break
        }
        return page
    }
}
