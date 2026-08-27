package dev.opencode.mobile.ui.scroll

internal data class MessageListInitialEndSnapState(
    val alreadySnapped: Boolean = false,
) {
    fun shouldSnap(messageCount: Int): Boolean = !alreadySnapped && messageCount > 0

    fun markSnapped(): MessageListInitialEndSnapState = copy(alreadySnapped = true)
}
