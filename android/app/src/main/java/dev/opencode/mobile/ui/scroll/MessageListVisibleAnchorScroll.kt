package dev.opencode.mobile.ui.scroll

/**
 * Unarmed visible-anchor capture/restore for reverse-layout message timelines.
 *
 * View-owned only (not Screen Model). When the user has scrolled away from end,
 * layout-affecting applies capture the first visible message id + LazyList
 * scrollOffset and restore with [androidx.compose.foundation.lazy.LazyListState.scrollToItem]
 * after layout settles.
 *
 * Armed / pinned follow settles to exact end separately — skip capture/restore
 * in that case. Active drag/deceleration wins (no restore under scroll-in-progress).
 */
internal data class MessageListVisibleAnchor(
    val messageId: String,
    val scrollOffset: Int,
)

internal data class MessageListVisibleAnchorRestoreTarget(
    val lazyIndex: Int,
    val scrollOffset: Int,
)

internal object MessageListVisibleAnchorScroll {
    /**
     * Capture/track when a layout-affecting apply is about to land and the host
     * will not settle to end via armed follow.
     */
    fun shouldCapture(
        hasLayoutAffectingChange: Boolean,
        isArmedOrPinned: Boolean,
        isScrollInProgress: Boolean,
    ): Boolean = hasLayoutAffectingChange && !isArmedOrPinned && !isScrollInProgress

    /** Continuously refresh the unarmed anchor while the user is idle away from end. */
    fun shouldTrack(
        isArmedOrPinned: Boolean,
        isScrollInProgress: Boolean,
    ): Boolean = !isArmedOrPinned && !isScrollInProgress

    /**
     * Restore only when an anchor was captured and armed follow still will not
     * take over after layout. Skip when the user is mid-gesture.
     */
    fun shouldRestore(
        anchor: MessageListVisibleAnchor?,
        isArmedOrPinned: Boolean,
        isScrollInProgress: Boolean,
    ): Boolean = anchor != null && !isArmedOrPinned && !isScrollInProgress

    /**
     * Chronological message IDs (oldest → newest) to reverse-layout LazyList index.
     * Index 0 is the bottom anchor spacer; [leadingItemCount] counts it plus any
     * status row below the newest message, so messages occupy
     * `leadingItemCount until leadingItemCount + messageCount`.
     */
    fun lazyIndexForMessageId(
        messageIdsChronological: List<String>,
        messageId: String,
        leadingItemCount: Int = 1,
    ): Int? {
        val chronologicalIndex = messageIdsChronological.indexOf(messageId)
        if (chronologicalIndex < 0) return null
        return leadingItemCount + messageIdsChronological.lastIndex - chronologicalIndex
    }

    fun messageIdAtLazyIndex(
        messageIdsChronological: List<String>,
        lazyIndex: Int,
    ): String? {
        if (lazyIndex <= 0) return null
        val chronologicalIndex = messageIdsChronological.size - lazyIndex
        return messageIdsChronological.getOrNull(chronologicalIndex)
    }

    fun capture(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        firstVisibleMessageId: String?,
    ): MessageListVisibleAnchor? {
        if (firstVisibleItemIndex <= 0) return null
        val messageId = firstVisibleMessageId ?: return null
        return MessageListVisibleAnchor(
            messageId = messageId,
            scrollOffset = firstVisibleItemScrollOffset,
        )
    }

    /** Index-based capture for pure tests of reverse-layout offset math. */
    fun captureFromChronologicalIds(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
        messageIdsChronological: List<String>,
    ): MessageListVisibleAnchor? {
        val messageId =
            messageIdAtLazyIndex(
                messageIdsChronological = messageIdsChronological,
                lazyIndex = firstVisibleItemIndex,
            )
        return capture(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
            firstVisibleMessageId = messageId,
        )
    }

    fun restoreTarget(
        anchor: MessageListVisibleAnchor,
        messageIdsChronological: List<String>,
        leadingItemCount: Int = 1,
    ): MessageListVisibleAnchorRestoreTarget? {
        val lazyIndex =
            lazyIndexForMessageId(
                messageIdsChronological = messageIdsChronological,
                messageId = anchor.messageId,
                leadingItemCount = leadingItemCount,
            ) ?: return null
        return MessageListVisibleAnchorRestoreTarget(
            lazyIndex = lazyIndex,
            scrollOffset = anchor.scrollOffset,
        )
    }
}
