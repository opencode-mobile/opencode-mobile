package dev.opencode.mobile.ui.scroll

internal object MessageListOlderHistory {
    fun shouldLoadOlder(
        hasOlder: Boolean,
        isLoadingOlder: Boolean,
        oldestVisible: Boolean,
    ): Boolean = hasOlder && !isLoadingOlder && oldestVisible
}
