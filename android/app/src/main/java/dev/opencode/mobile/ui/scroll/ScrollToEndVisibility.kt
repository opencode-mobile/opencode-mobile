package dev.opencode.mobile.ui.scroll

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState

private const val BOTTOM_ANCHOR_INDEX = 0
private const val BOTTOM_ANCHOR_HEIGHT_PX = 1
private const val VIEWPORT_REVEAL_FRACTION = 0.2f

private fun newestMessageIndex(messageCount: Int): Int = if (messageCount > 0) 1 else -1

private fun revealThresholdPx(
    viewportHeightPx: Int,
    revealThresholdMinPx: Int,
): Int =
    maxOf(
        revealThresholdMinPx,
        (viewportHeightPx * VIEWPORT_REVEAL_FRACTION).toInt(),
    )

internal fun timelineEndGapPx(
    messageCount: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int,
    visibleItems: List<TimelineVisibleItem>,
): Int? {
    if (messageCount == 0) return null

    val bottomAnchor = visibleItems.firstOrNull { it.index == BOTTOM_ANCHOR_INDEX }
    if (bottomAnchor != null) {
        return bottomAnchor.offset +
            bottomAnchor.size +
            afterContentPadding -
            viewportEndOffset
    }

    val newestMessage =
        visibleItems.firstOrNull { it.index == newestMessageIndex(messageCount) }
            ?: return null
    return newestMessage.offset +
        newestMessage.size +
        afterContentPadding +
        BOTTOM_ANCHOR_HEIGHT_PX -
        viewportEndOffset
}

/**
 * Visible item snapshot used by scroll-to-end math. Kept free of Compose types so
 * unit tests can cover taller-than-viewport newest messages without a runtime.
 */
internal data class TimelineVisibleItem(
    val index: Int,
    val offset: Int,
    val size: Int,
)

internal fun shouldShowScrollToEnd(
    messageCount: Int,
    viewportHeightPx: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int,
    revealThresholdMinPx: Int,
    visibleItems: List<TimelineVisibleItem>,
): Boolean {
    if (messageCount == 0) return false
    if (viewportHeightPx <= 0) return false

    val gap =
        timelineEndGapPx(
            messageCount = messageCount,
            viewportEndOffset = viewportEndOffset,
            afterContentPadding = afterContentPadding,
            visibleItems = visibleItems,
        ) ?: return true
    return gap > revealThresholdPx(viewportHeightPx, revealThresholdMinPx)
}

internal fun isUserTimelineScrolling(
    isScrollInProgress: Boolean,
    isProgrammaticScrolling: Boolean,
): Boolean = isScrollInProgress && !isProgrammaticScrolling

internal fun shouldShowScrollToEndPill(
    isUserScrolling: Boolean,
    isSettlingToEnd: Boolean,
    currentlyShown: Boolean,
    messageCount: Int,
    viewportHeightPx: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int,
    revealThresholdMinPx: Int,
    hideSlackPx: Int,
    visibleItems: List<TimelineVisibleItem>,
): Boolean {
    if (isUserScrolling || isSettlingToEnd) return false
    if (messageCount == 0 || viewportHeightPx <= 0) return false

    val gap =
        timelineEndGapPx(
            messageCount = messageCount,
            viewportEndOffset = viewportEndOffset,
            afterContentPadding = afterContentPadding,
            visibleItems = visibleItems,
        ) ?: return true
    val revealPx = revealThresholdPx(viewportHeightPx, revealThresholdMinPx)
    val hideThresholdPx = (revealPx - hideSlackPx).coerceAtLeast(0)
    return if (currentlyShown) gap > hideThresholdPx else gap > revealPx
}

fun LazyListState.shouldShowScrollToEnd(
    messageCount: Int,
    revealThresholdMinPx: Int,
): Boolean {
    val layoutInfo = layoutInfo
    return shouldShowScrollToEnd(
        messageCount = messageCount,
        viewportHeightPx = layoutInfo.viewportSize.height,
        viewportEndOffset = layoutInfo.viewportEndOffset,
        afterContentPadding = layoutInfo.afterContentPadding,
        revealThresholdMinPx = revealThresholdMinPx,
        visibleItems = layoutInfo.visibleItemsInfo.map { it.toTimelineVisibleItem() },
    )
}

fun LazyListState.shouldShowScrollToEndPill(
    messageCount: Int,
    revealThresholdMinPx: Int,
    hideSlackPx: Int,
    isUserScrolling: Boolean,
    isSettlingToEnd: Boolean,
    currentlyShown: Boolean,
): Boolean {
    val layoutInfo = layoutInfo
    return shouldShowScrollToEndPill(
        isUserScrolling = isUserScrolling,
        isSettlingToEnd = isSettlingToEnd,
        currentlyShown = currentlyShown,
        messageCount = messageCount,
        viewportHeightPx = layoutInfo.viewportSize.height,
        viewportEndOffset = layoutInfo.viewportEndOffset,
        afterContentPadding = layoutInfo.afterContentPadding,
        revealThresholdMinPx = revealThresholdMinPx,
        hideSlackPx = hideSlackPx,
        visibleItems = layoutInfo.visibleItemsInfo.map { it.toTimelineVisibleItem() },
    )
}

fun LazyListState.isPinnedToTimelineEnd(
    messageCount: Int,
    tolerancePx: Int,
    bottomAnchorHeightPx: Int,
): Boolean =
    isPinnedToTimelineEnd(
        messageCount = messageCount,
        tolerancePx = tolerancePx,
        bottomAnchorHeightPx = bottomAnchorHeightPx,
        visibleItems = layoutInfo.visibleItemsInfo.map { it.toTimelineVisibleItem() },
    )

/**
 * How far the reverse-layout timeline is scrolled away from its end, in px.
 *
 * With `reverseLayout = true`, LazyList reports item offsets along the reversed
 * main axis: the bottom anchor rests at offset `0` and every row's offset turns
 * negative as the user scrolls toward older rows (content padding is outside
 * this range). Returns `null` when neither the bottom anchor nor the newest row
 * is laid out — the list is far from the end.
 */
internal fun timelineEndScrollDistancePx(
    messageCount: Int,
    bottomAnchorHeightPx: Int,
    visibleItems: List<TimelineVisibleItem>,
): Int? {
    if (messageCount == 0) return 0
    val bottomAnchor = visibleItems.firstOrNull { it.index == BOTTOM_ANCHOR_INDEX }
    if (bottomAnchor != null) return -bottomAnchor.offset
    val newestMessage =
        visibleItems.firstOrNull { it.index == newestMessageIndex(messageCount) } ?: return null
    return bottomAnchorHeightPx - newestMessage.offset
}

/**
 * Pinned means scrolled no further than [tolerancePx] from the end. A newest
 * row (or bottom anchor) that is merely still laid out — e.g. a tall reply whose
 * top is on screen, or the anchor under the composer padding — is not pinned.
 */
internal fun isPinnedToTimelineEnd(
    messageCount: Int,
    tolerancePx: Int,
    bottomAnchorHeightPx: Int,
    visibleItems: List<TimelineVisibleItem>,
): Boolean {
    val distance =
        timelineEndScrollDistancePx(
            messageCount = messageCount,
            bottomAnchorHeightPx = bottomAnchorHeightPx,
            visibleItems = visibleItems,
        ) ?: return false
    return distance <= tolerancePx
}

suspend fun LazyListState.animateScrollToTimelineEnd() {
    animateScrollToItem(BOTTOM_ANCHOR_INDEX)
}

suspend fun LazyListState.scrollToTimelineEnd() {
    scrollToItem(BOTTOM_ANCHOR_INDEX)
}

private fun LazyListState.distanceFromTimelineEnd(messageCount: Int): Int? {
    val bottomAnchor =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == BOTTOM_ANCHOR_INDEX }
    if (bottomAnchor != null) {
        return bottomAnchor.offset +
            bottomAnchor.size +
            layoutInfo.afterContentPadding -
            layoutInfo.viewportEndOffset
    }

    val newestMessageIndex = newestMessageIndex(messageCount)
    if (newestMessageIndex < 0) return null

    val newestMessage =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == newestMessageIndex }
            ?: return null

    return newestMessage.offset +
        newestMessage.size +
        layoutInfo.afterContentPadding -
        layoutInfo.viewportEndOffset +
        BOTTOM_ANCHOR_HEIGHT_PX
}

suspend fun LazyListState.animateScrollToTimelineEndFromCurrentPosition(messageCount: Int) {
    val distanceFromEnd = distanceFromTimelineEnd(messageCount)

    if (distanceFromEnd != null && distanceFromEnd > 0 && distanceFromEnd <= layoutInfo.viewportSize.height) {
        animateScrollBy(distanceFromEnd.toFloat())
    } else {
        animateScrollToTimelineEnd()
    }
}

suspend fun LazyListState.scrollToTimelineEndAfterLayoutChange(messageCount: Int) {
    if (messageCount == 0) return

    val distanceFromEnd = distanceFromTimelineEnd(messageCount)
    if (distanceFromEnd != null && distanceFromEnd > 0 && distanceFromEnd <= layoutInfo.viewportSize.height) {
        scrollBy(distanceFromEnd.toFloat())
    } else {
        scrollToTimelineEnd()
    }
}

private fun LazyListItemInfo.toTimelineVisibleItem(): TimelineVisibleItem = TimelineVisibleItem(index = index, offset = offset, size = size)
