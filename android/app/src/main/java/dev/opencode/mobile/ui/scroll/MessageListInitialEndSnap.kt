package dev.opencode.mobile.ui.scroll

internal data class MessageListInitialEndSnapState(
    val alreadySnapped: Boolean = false,
) {
    /**
     * Snap only the first time a timeline is shown at the reverse-layout end.
     *
     * Leaving Session Chat for a child route (subagent, tool group, Changes)
     * disposes the composition. [androidx.compose.foundation.lazy.LazyListState]
     * is restored, but a fresh snap state would scroll that restored place back
     * to the end. A non-zero index or offset means the user was not at the end.
     */
    fun shouldSnap(
        messageCount: Int,
        firstVisibleItemIndex: Int = 0,
        firstVisibleItemScrollOffset: Int = 0,
    ): Boolean =
        !alreadySnapped &&
            messageCount > 0 &&
            firstVisibleItemIndex == 0 &&
            firstVisibleItemScrollOffset == 0

    fun markSnapped(): MessageListInitialEndSnapState = copy(alreadySnapped = true)
}
