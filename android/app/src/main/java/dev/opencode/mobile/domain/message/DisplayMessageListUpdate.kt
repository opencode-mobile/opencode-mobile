package dev.opencode.mobile.domain.message

sealed interface DisplayMessageListUpdate {
    data class Messages(
        val messages: List<DisplayMessage>,
        val isFromCache: Boolean = false,
    ) : DisplayMessageListUpdate

    data class LoadFailed(
        val error: Throwable,
    ) : DisplayMessageListUpdate
}
