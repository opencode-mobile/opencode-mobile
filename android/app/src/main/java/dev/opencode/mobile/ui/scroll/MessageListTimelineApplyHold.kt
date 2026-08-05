package dev.opencode.mobile.ui.scroll

/**
 * Host-side latest-wins hold for Session Chat timeline applies while the user
 * is dragging or flinging — parity with iOS `pendingApplyItems`.
 *
 * Required frames flush immediately under scroll; other kinds may pend.
 * View-owned only (not Screen Model).
 *
 * Only **user-initiated** scroll should hold. Programmatic auto-follow
 * (`LazyListState.isScrollInProgress` during pin-to-end) must keep applying
 * streaming frames, matching iOS (`isDragging` / `isDecelerating` /
 * `isUserScrolling` only).
 */
internal object MessageListTimelineApplyHold {
    /**
     * Hold incoming messages + timelineFrame when a user scroll gesture is
     * active (or still decelerating from one) and the frame is not Required.
     * Null frames are treated as non-Required.
     */
    fun shouldHold(
        isUserScrollInProgress: Boolean,
        isRequired: Boolean,
    ): Boolean = isUserScrollInProgress && !isRequired
}
