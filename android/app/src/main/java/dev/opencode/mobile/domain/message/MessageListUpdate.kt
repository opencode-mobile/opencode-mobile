package dev.opencode.mobile.domain.message

sealed interface MessageListUpdate {
    data class Messages(
        val messages: List<Message>,
    ) : MessageListUpdate

    data class LoadFailed(
        val error: Throwable,
    ) : MessageListUpdate
}
