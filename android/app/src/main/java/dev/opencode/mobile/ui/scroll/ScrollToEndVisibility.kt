package dev.opencode.mobile.ui.scroll

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState

private const val BOTTOM_ANCHOR_INDEX = 0
private const val BOTTOM_ANCHOR_HEIGHT_PX = 1
private const val VIEWPORT_REVEAL_FRACTION = 0.2f

private fun newestMessageIndex(messageCount: Int): Int = if (messageCount > 0) 1 else -1

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

    val revealThresholdPx =
        maxOf(
            revealThresholdMinPx,
            (viewportHeightPx * VIEWPORT_REVEAL_FRACTION).toInt(),
        )

    val bottomAnchor = visibleItems.firstOrNull { it.index == BOTTOM_ANCHOR_INDEX }
    if (bottomAnchor != null) {
        val trailingGap =
            bottomAnchor.offset +
                bottomAnchor.size +
                afterContentPadding -
                viewportEndOffset
        return trailingGap > revealThresholdPx
    }

    // Bottom anchor left the viewport. On a reverse timeline that means the user
    // scrolled away from end — including when a taller-than-viewport newest
    // message still fills the screen (iOS uses content-offset distance; item
    // bounds alone cannot express that case).
    return true
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
): Boolean = !isScrollInProgress && shouldShowScrollToEnd(messageCount, revealThresholdMinPx)

fun LazyListState.isPinnedToTimelineEnd(
    messageCount: Int,
    tolerancePx: Int,
    bottomAnchorHeightPx: Int,
): Boolean {
    if (messageCount == 0) return true

    val bottomAnchor =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == BOTTOM_ANCHOR_INDEX }
    if (bottomAnchor != null) {
        val contentEnd =
            bottomAnchor.offset +
                bottomAnchor.size +
                layoutInfo.afterContentPadding
        return contentEnd <= layoutInfo.viewportEndOffset + tolerancePx
    }

    val newestMessageIndex = newestMessageIndex(messageCount)
    val newestMessage =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == newestMessageIndex }
            ?: return false

    val lastVisible = layoutInfo.visibleItemsInfo.maxByOrNull { it.index } ?: return false
    if (lastVisible.index < newestMessageIndex) return false

    val estimatedContentEnd =
        newestMessage.offset +
            newestMessage.size +
            layoutInfo.afterContentPadding +
            bottomAnchorHeightPx
    return estimatedContentEnd <= layoutInfo.viewportEndOffset + tolerancePx
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

private fun LazyListItemInfo.toTimelineVisibleItem(): TimelineVisibleItem =
    TimelineVisibleItem(index = index, offset = offset, size = size)
