package dev.opencode.mobile.domain.message

object MessageHistoryPages {
    suspend fun fetchTail(listPage: suspend (MessageHistoryQuery) -> MessagePage): MessagePage = listPage(MessageHistoryQuery.tail())
}
