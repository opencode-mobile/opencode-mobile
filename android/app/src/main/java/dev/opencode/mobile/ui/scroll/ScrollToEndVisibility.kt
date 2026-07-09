package dev.opencode.mobile.ui.scroll

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState

private const val BOTTOM_ANCHOR_INDEX = 0
private const val BOTTOM_ANCHOR_HEIGHT_PX = 1
private const val VIEWPORT_REVEAL_FRACTION = 0.2f

private fun newestMessageIndex(messageCount: Int): Int = if (messageCount > 0) 1 else -1

fun LazyListState.shouldShowScrollToEnd(
    messageCount: Int,
    revealThresholdMinPx: Int,
): Boolean {
    if (messageCount == 0) return false

    val layoutInfo = layoutInfo
    val viewportHeightPx = layoutInfo.viewportSize.height
    if (viewportHeightPx <= 0) return false

    val revealThresholdPx =
        maxOf(
            revealThresholdMinPx,
            (viewportHeightPx * VIEWPORT_REVEAL_FRACTION).toInt(),
        )

    val bottomAnchor =
        layoutInfo.visibleItemsInfo.lastOrNull { it.index == BOTTOM_ANCHOR_INDEX }
    if (bottomAnchor != null) {
        val trailingGap =
            bottomAnchor.offset +
                bottomAnchor.size +
                layoutInfo.afterContentPadding -
                layoutInfo.viewportEndOffset
        return trailingGap > revealThresholdPx
    }

    val newestMessageIndex = newestMessageIndex(messageCount)
    val newestMessage =
        layoutInfo.visibleItemsInfo.lastOrNull { it.index == newestMessageIndex }
            ?: return true

    val lastVisible = layoutInfo.visibleItemsInfo.maxByOrNull { it.index } ?: return false
    if (lastVisible.index < newestMessageIndex) {
        return true
    }

    val visibleTailPx = layoutInfo.viewportEndOffset - (newestMessage.offset + newestMessage.size)
    val belowViewportPx = layoutInfo.afterContentPadding + BOTTOM_ANCHOR_HEIGHT_PX
    val distanceFromBottom = visibleTailPx + belowViewportPx
    return distanceFromBottom > revealThresholdPx
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
