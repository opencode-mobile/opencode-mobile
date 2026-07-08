package dev.opencode.mobile.ui.scroll

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState

private const val BottomAnchorHeightPx = 1
private const val ViewportRevealFraction = 0.2f

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
            (viewportHeightPx * ViewportRevealFraction).toInt(),
        )

    val lastMessageIndex = messageCount - 1
    val bottomAnchorIndex = messageCount

    val bottomAnchor =
        layoutInfo.visibleItemsInfo.lastOrNull { it.index == bottomAnchorIndex }
    if (bottomAnchor != null) {
        val trailingGap =
            bottomAnchor.offset +
                bottomAnchor.size +
                layoutInfo.afterContentPadding -
                layoutInfo.viewportEndOffset
        return trailingGap > revealThresholdPx
    }

    val lastMessage =
        layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastMessageIndex }
            ?: return true

    val lastVisible = layoutInfo.visibleItemsInfo.maxByOrNull { it.index } ?: return false
    if (lastVisible.index < lastMessageIndex) {
        return true
    }

    val visibleTailPx = layoutInfo.viewportEndOffset - (lastMessage.offset + lastMessage.size)
    val belowViewportPx = layoutInfo.afterContentPadding + BottomAnchorHeightPx
    val distanceFromBottom = visibleTailPx + belowViewportPx
    return distanceFromBottom > revealThresholdPx
}

fun LazyListState.shouldShowScrollToEndPill(
    messageCount: Int,
    revealThresholdMinPx: Int,
): Boolean =
    !isScrollInProgress && shouldShowScrollToEnd(messageCount, revealThresholdMinPx)

fun LazyListState.isPinnedToTimelineEnd(
    messageCount: Int,
    tolerancePx: Int,
    bottomAnchorHeightPx: Int,
): Boolean {
    if (messageCount == 0) return true

    val bottomAnchorIndex = messageCount
    val bottomAnchor =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == bottomAnchorIndex }
    if (bottomAnchor != null) {
        val contentEnd =
            bottomAnchor.offset +
                bottomAnchor.size +
                layoutInfo.afterContentPadding
        return contentEnd <= layoutInfo.viewportEndOffset + tolerancePx
    }

    val lastMessageIndex = messageCount - 1
    val lastMessage =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastMessageIndex }
            ?: return false

    val lastVisible = layoutInfo.visibleItemsInfo.maxByOrNull { it.index } ?: return false
    if (lastVisible.index < lastMessageIndex) return false

    val estimatedContentEnd =
        lastMessage.offset +
            lastMessage.size +
            layoutInfo.afterContentPadding +
            bottomAnchorHeightPx
    return estimatedContentEnd <= layoutInfo.viewportEndOffset + tolerancePx
}

suspend fun LazyListState.animateScrollToTimelineEndAfterAppend(messageCount: Int) {
    if (messageCount == 0) return

    val distanceFromEnd = distanceFromTimelineEnd(messageCount)
    if (distanceFromEnd != null && distanceFromEnd > 0 && distanceFromEnd <= layoutInfo.viewportSize.height) {
        animateScrollBy(distanceFromEnd.toFloat())
    } else {
        animateScrollToTimelineEnd(messageCount)
    }
}

suspend fun LazyListState.animateScrollToTimelineEndFromCurrentPosition(messageCount: Int) {
    animateScrollToTimelineEndAfterAppend(messageCount)
}

suspend fun LazyListState.scrollToTimelineEndAfterLayoutChange(messageCount: Int) {
    if (messageCount == 0) return

    val distanceFromEnd = distanceFromTimelineEnd(messageCount)
    if (distanceFromEnd != null && distanceFromEnd > 0 && distanceFromEnd <= layoutInfo.viewportSize.height) {
        scrollBy(distanceFromEnd.toFloat())
    } else {
        scrollToTimelineEnd(messageCount)
    }
}

suspend fun LazyListState.animateScrollToTimelineEnd(messageCount: Int) {
    if (messageCount > 0) {
        animateScrollToItem(messageCount)
    }
}

suspend fun LazyListState.scrollToTimelineEnd(messageCount: Int) {
    if (messageCount > 0) {
        scrollToItem(messageCount)
    }
}

suspend fun LazyListState.scrollExactlyToTimelineEnd(messageCount: Int) {
    scrollToTimelineEnd(messageCount)
}

private fun LazyListState.distanceFromTimelineEnd(messageCount: Int): Int? {
    val bottomAnchorIndex = messageCount
    val bottomAnchor =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == bottomAnchorIndex }
            ?: return null

    return bottomAnchor.offset +
        bottomAnchor.size +
        layoutInfo.afterContentPadding -
        layoutInfo.viewportEndOffset
}
