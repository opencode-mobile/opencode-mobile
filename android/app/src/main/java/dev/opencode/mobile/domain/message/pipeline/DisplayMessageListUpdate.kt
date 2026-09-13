package dev.opencode.mobile.domain.message

sealed interface DisplayMessageListUpdate {
    data class Messages(
        val messages: List<DisplayMessage>,
        val isFromCache: Boolean = false,
        val hasOlder: Boolean = false,
    ) : DisplayMessageListUpdate

    data class LoadFailed(
        val error: Throwable,
    ) : DisplayMessageListUpdate

    /** Network confirmed cache without a second UI snapshot (fingerprint unchanged). */
    data class CacheSyncConfirmed(
        val hasOlder: Boolean = false,
    ) : DisplayMessageListUpdate
}
