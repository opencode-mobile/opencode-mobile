package dev.opencode.mobile.domain.message

sealed interface MessageListUpdate {
    data class Messages(
        val messages: List<Message>,
        val isFromCache: Boolean = false,
        val hasOlder: Boolean = false,
    ) : MessageListUpdate

    data class LoadFailed(
        val error: Throwable,
    ) : MessageListUpdate

    /** Network confirmed cache without a second UI snapshot (fingerprint unchanged). */
    data class CacheSyncConfirmed(
        val hasOlder: Boolean = false,
    ) : MessageListUpdate
}
