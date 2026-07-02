package dev.opencode.mobile.ui.scroll

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
        val trailingGap = bottomAnchor.offset + bottomAnchor.size - layoutInfo.viewportEndOffset
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
