package dev.opencode.mobile.ui.scroll

/**
 * Host-side latest-wins hold for Session Chat timeline applies while the user
 * is dragging or flinging — parity with iOS `pendingApplyItems`.
 *
 * Required frames flush immediately under scroll; other kinds may pend.
 * View-owned only (not Screen Model).
 */
internal object MessageListTimelineApplyHold {
    /**
     * Hold incoming messages + timelineFrame when the list is scrolling and the
     * frame is not Required. Null frames are treated as non-Required.
     */
    fun shouldHold(
        isScrollInProgress: Boolean,
        isRequired: Boolean,
    ): Boolean = isScrollInProgress && !isRequired
}
