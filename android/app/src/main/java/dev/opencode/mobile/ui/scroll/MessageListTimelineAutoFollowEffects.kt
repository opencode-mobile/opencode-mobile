package dev.opencode.mobile.ui.scroll

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.distinctUntilChanged

internal val MessageListMessageResizeAnimationSpec =
    tween<IntSize>(
        durationMillis = 200,
        easing = FastOutSlowInEasing,
    )

internal fun Modifier.messageListAnimateLastMessageSize(
    animateSizeChanges: Boolean,
    onContentSizeAnimationFinished: (() -> Unit)?,
): Modifier =
    if (animateSizeChanges) {
        animateContentSize(
            animationSpec = MessageListMessageResizeAnimationSpec,
            finishedListener = { _, _ -> onContentSizeAnimationFinished?.invoke() },
        )
    } else {
        this
    }

@Composable
internal fun rememberMessageListScrollToEndVisibility(
    listState: LazyListState,
    messageCount: Int,
    revealThresholdMinPx: Int,
): Boolean {
    val showScrollToEnd by remember(
        listState,
        messageCount,
        revealThresholdMinPx,
    ) {
        derivedStateOf {
            listState.shouldShowScrollToEndPill(
                messageCount = messageCount,
                revealThresholdMinPx = revealThresholdMinPx,
            )
        }
    }

    return showScrollToEnd
}

@Composable
internal fun MessageListInitialEndSnapEffect(
    firstMessageId: String?,
    lastMessageId: String?,
    messageCount: Int,
    autoFollowTolerancePx: Int,
    bottomAnchorHeightPx: Int,
    scrollController: MessageListTimelineScrollController,
) {
    var initialEndScrollDone by remember { mutableStateOf(false) }

    LaunchedEffect(firstMessageId, lastMessageId) {
        if (!initialEndScrollDone && messageCount > 0) {
            withFrameNanos { }
            withFrameNanos { }
            scrollController.snapToEnd(
                messageCount = messageCount,
                tolerancePx = autoFollowTolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
            )
            initialEndScrollDone = true
        }
    }
}

@Composable
internal fun MessageListAutoFollowArmingEffect(
    listState: LazyListState,
    messageCount: Int,
    autoFollowEnabled: Boolean,
    autoFollowTolerancePx: Int,
    bottomAnchorHeightPx: Int,
    isPinnedToEnd: Boolean,
    autoFollowState: MessageListAutoFollowState,
    scrollController: MessageListTimelineScrollController,
) {
    val currentIsPinnedToEnd by rememberUpdatedState(isPinnedToEnd)
    val currentAutoFollowEnabled by rememberUpdatedState(autoFollowEnabled)

    LaunchedEffect(messageCount, isPinnedToEnd) {
        if (isPinnedToEnd) {
            autoFollowState.updatePinnedState(
                isPinnedToEnd = true,
                messageCount = messageCount,
            )
        }
    }

    LaunchedEffect(
        listState,
        messageCount,
        autoFollowTolerancePx,
        bottomAnchorHeightPx,
    ) {
        snapshotFlow {
            val bottomAnchor =
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            MessageListAutoFollowLayoutSnapshot(
                isPinnedToEnd = currentIsPinnedToEnd,
                isScrollInProgress = listState.isScrollInProgress,
                viewportEndOffset = listState.layoutInfo.viewportEndOffset,
                bottomAnchorOffset = bottomAnchor?.offset,
                bottomAnchorSize = bottomAnchor?.size,
            )
        }.distinctUntilChanged()
            .collect { snapshot ->
                when {
                    snapshot.isScrollInProgress || snapshot.isPinnedToEnd ->
                        autoFollowState.updatePinnedState(
                            isPinnedToEnd = snapshot.isPinnedToEnd,
                            messageCount = messageCount,
                        )

                    autoFollowState.shouldFollowLayoutChange(
                        autoFollowEnabled = currentAutoFollowEnabled,
                        isPinnedToEnd = false,
                    ) ->
                        scrollController.snapToEnd(
                            messageCount = messageCount,
                            tolerancePx = autoFollowTolerancePx,
                            bottomAnchorHeightPx = bottomAnchorHeightPx,
                        )

                    else ->
                        autoFollowState.updatePinnedState(
                            isPinnedToEnd = false,
                            messageCount = messageCount,
                        )
                }
            }
    }
}

@Composable
internal fun MessageListAutoFollowAppendEffect(
    messageCount: Int,
    autoFollowEnabled: Boolean,
    autoFollowTolerancePx: Int,
    bottomAnchorHeightPx: Int,
    autoFollowState: MessageListAutoFollowState,
    scrollController: MessageListTimelineScrollController,
) {
    LaunchedEffect(messageCount, autoFollowEnabled) {
        if (
            autoFollowState.onMessageCountChanged(
                messageCount = messageCount,
                autoFollowEnabled = autoFollowEnabled,
            )
        ) {
            withFrameNanos { }
            withFrameNanos { }
            scrollController.snapToEnd(
                messageCount = messageCount,
                tolerancePx = autoFollowTolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
            )
        }
    }
}

@Composable
internal fun rememberMessageListLastMessageContentSizeSettled(
    autoFollowEnabled: Boolean,
    messageCount: Int,
    autoFollowTolerancePx: Int,
    bottomAnchorHeightPx: Int,
    autoFollowState: MessageListAutoFollowState,
    scrollController: MessageListTimelineScrollController,
): () -> Unit {
    val currentAutoFollowEnabled by rememberUpdatedState(autoFollowEnabled)
    val currentMessageCount by rememberUpdatedState(messageCount)

    return remember(scrollController, autoFollowState, autoFollowTolerancePx, bottomAnchorHeightPx) {
        {
            if (autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled = currentAutoFollowEnabled)) {
                scrollController.snapToEnd(
                    messageCount = currentMessageCount,
                    tolerancePx = autoFollowTolerancePx,
                    bottomAnchorHeightPx = bottomAnchorHeightPx,
                )
            }
        }
    }
}

private data class MessageListAutoFollowLayoutSnapshot(
    val isPinnedToEnd: Boolean,
    val isScrollInProgress: Boolean,
    val viewportEndOffset: Int,
    val bottomAnchorOffset: Int?,
    val bottomAnchorSize: Int?,
)
