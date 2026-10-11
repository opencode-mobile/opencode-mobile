package dev.opencode.mobile.ui.scroll

import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.distinctUntilChanged

private const val LAYOUT_AFFECTING_LOG_TAG = "SessionChatScroll"

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

internal data class MessageListUserScroll(
    val inProgress: Boolean,
    val connection: NestedScrollConnection,
)

@Composable
internal fun rememberMessageListUserScroll(listState: LazyListState): MessageListUserScroll {
    var inProgress by remember { mutableStateOf(false) }
    val connection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) {
                        inProgress = true
                    }
                    return Offset.Zero
                }
            }
        }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                inProgress = false
            }
        }
    }
    return MessageListUserScroll(inProgress = inProgress, connection = connection)
}

@Composable
internal fun rememberMessageListScrollToEndVisibility(
    listState: LazyListState,
    messageCount: Int,
    revealThresholdMinPx: Int,
    hideSlackPx: Int,
    isProgrammaticScrolling: Boolean,
    isSettlingToEnd: Boolean,
): Boolean {
    var currentlyShown by remember(listState) { mutableStateOf(false) }
    val showScrollToEnd by remember(
        listState,
        messageCount,
        revealThresholdMinPx,
        hideSlackPx,
        isProgrammaticScrolling,
        isSettlingToEnd,
        currentlyShown,
    ) {
        derivedStateOf {
            listState.shouldShowScrollToEndPill(
                messageCount = messageCount,
                revealThresholdMinPx = revealThresholdMinPx,
                hideSlackPx = hideSlackPx,
                isUserScrolling =
                    isUserTimelineScrolling(
                        isScrollInProgress = listState.isScrollInProgress,
                        isProgrammaticScrolling = isProgrammaticScrolling,
                    ),
                isSettlingToEnd = isSettlingToEnd,
                currentlyShown = currentlyShown,
            )
        }
    }
    SideEffect {
        currentlyShown = showScrollToEnd
    }
    return showScrollToEnd
}

@Composable
internal fun MessageListInitialEndSnapEffect(
    listState: LazyListState,
    messageCount: Int,
    autoFollowEnabled: Boolean,
    autoFollowTolerancePx: Int,
    bottomAnchorHeightPx: Int,
    scrollController: MessageListTimelineScrollController,
) {
    // `remember` resets when a child route disposes this screen. Save the flag
    // with the nav entry so popping back does not snap a restored list to the end.
    var alreadySnapped by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(scrollController, messageCount) {
        if (messageCount <= 0 || alreadySnapped) return@LaunchedEffect
        val shouldSnap =
            MessageListInitialEndSnapState(alreadySnapped = alreadySnapped).shouldSnap(
                messageCount = messageCount,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            )
        alreadySnapped = true
        if (!shouldSnap) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        scrollController.snapToEnd(
            messageCount = messageCount,
            tolerancePx = autoFollowTolerancePx,
            bottomAnchorHeightPx = bottomAnchorHeightPx,
            autoFollowEnabled = autoFollowEnabled,
        )
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

                    autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled = currentAutoFollowEnabled) ->
                        followMessageListLayoutChangeIfNeeded(
                            listState = listState,
                            messageCount = messageCount,
                            autoFollowEnabled = currentAutoFollowEnabled,
                            autoFollowTolerancePx = autoFollowTolerancePx,
                            bottomAnchorHeightPx = bottomAnchorHeightPx,
                            autoFollowState = autoFollowState,
                            scrollController = scrollController,
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
    newestMessageId: String?,
    autoFollowEnabled: Boolean,
    autoFollowTolerancePx: Int,
    bottomAnchorHeightPx: Int,
    autoFollowState: MessageListAutoFollowState,
    scrollController: MessageListTimelineScrollController,
) {
    val currentNewestMessageId by rememberUpdatedState(newestMessageId)
    LaunchedEffect(messageCount, autoFollowEnabled) {
        if (
            autoFollowState.onMessageCountChanged(
                messageCount = messageCount,
                autoFollowEnabled = autoFollowEnabled,
                newestMessageId = currentNewestMessageId,
            )
        ) {
            withFrameNanos { }
            withFrameNanos { }
            scrollController.snapToEnd(
                messageCount = messageCount,
                tolerancePx = autoFollowTolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
                autoFollowEnabled = autoFollowEnabled,
            )
        }
    }
}

@Composable
internal fun MessageListAutoFollowLayoutAffectingEffect(
    layoutAffectingRevision: Long,
    messageCount: Int,
    autoFollowEnabled: Boolean,
    listState: LazyListState,
    autoFollowTolerancePx: Int,
    bottomAnchorHeightPx: Int,
    autoFollowState: MessageListAutoFollowState,
    scrollController: MessageListTimelineScrollController,
) {
    LaunchedEffect(layoutAffectingRevision, autoFollowEnabled) {
        // `0` means content-only / no layout-affecting declared IDs — skip follow.
        if (layoutAffectingRevision != 0L && messageCount > 0) {
            runCatching {
                Log.d(
                    LAYOUT_AFFECTING_LOG_TAG,
                    "layoutAffecting armed-follow rev=$layoutAffectingRevision " +
                        "count=$messageCount enabled=$autoFollowEnabled",
                )
            }
            followMessageListLayoutChangeIfNeeded(
                listState = listState,
                messageCount = messageCount,
                autoFollowEnabled = autoFollowEnabled,
                autoFollowTolerancePx = autoFollowTolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
                autoFollowState = autoFollowState,
                scrollController = scrollController,
            )
        }
    }
}

/**
 * Armed-only pin for streaming newest-row growth (`grewBottomRowIDs`).
 * Does not drive unarmed visible-anchor restore.
 */
@Composable
internal fun MessageListAutoFollowGrowBottomEffect(
    growBottomRevision: Long,
    messageCount: Int,
    autoFollowEnabled: Boolean,
    listState: LazyListState,
    autoFollowTolerancePx: Int,
    bottomAnchorHeightPx: Int,
    autoFollowState: MessageListAutoFollowState,
    scrollController: MessageListTimelineScrollController,
) {
    LaunchedEffect(growBottomRevision, autoFollowEnabled) {
        if (growBottomRevision != 0L && messageCount > 0) {
            runCatching {
                Log.d(
                    LAYOUT_AFFECTING_LOG_TAG,
                    "growBottom armed-follow rev=$growBottomRevision " +
                        "count=$messageCount enabled=$autoFollowEnabled",
                )
            }
            followMessageListLayoutChangeIfNeeded(
                listState = listState,
                messageCount = messageCount,
                autoFollowEnabled = autoFollowEnabled,
                autoFollowTolerancePx = autoFollowTolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
                autoFollowState = autoFollowState,
                scrollController = scrollController,
            )
        }
    }
}

/**
 * Unarmed visible-anchor ID+offset capture/restore for layout-affecting applies.
 * Separate from armed [MessageListAutoFollowLayoutAffectingEffect] — no unified
 * Screen scroll API. Skip when armed/pinned follow will settle to end, or while
 * the user is dragging/decelerating.
 *
 * Continuously tracks the idle unarmed anchor so capture stays valid even when
 * Compose has already swapped in the next frame's message IDs.
 */
@Composable
internal fun MessageListUnarmedVisibleAnchorEffect(
    layoutAffectingRevision: Long,
    messageIdsChronological: List<String>,
    autoFollowEnabled: Boolean,
    listState: LazyListState,
    autoFollowTolerancePx: Int,
    bottomAnchorHeightPx: Int,
    autoFollowState: MessageListAutoFollowState,
) {
    val currentMessageIds by rememberUpdatedState(messageIdsChronological)
    val currentAutoFollowEnabled by rememberUpdatedState(autoFollowEnabled)
    val trackedAnchor = remember { mutableStateOf<MessageListVisibleAnchor?>(null) }

    LaunchedEffect(
        listState,
        autoFollowState,
        autoFollowTolerancePx,
        bottomAnchorHeightPx,
    ) {
        snapshotFlow {
            val messageCount = currentMessageIds.size
            val armedOrPinned =
                messageCount > 0 &&
                    (
                        autoFollowState.shouldEnsureLayoutEnd(
                            autoFollowEnabled = currentAutoFollowEnabled,
                        ) ||
                            listState.isPinnedToTimelineEnd(
                                messageCount = messageCount,
                                tolerancePx = autoFollowTolerancePx,
                                bottomAnchorHeightPx = bottomAnchorHeightPx,
                            )
                    )
            val firstVisibleKey =
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == listState.firstVisibleItemIndex }
                    ?.key as? String
            UnarmedVisibleAnchorSnapshot(
                isArmedOrPinned = armedOrPinned,
                isScrollInProgress = listState.isScrollInProgress,
                anchor =
                    MessageListVisibleAnchorScroll.capture(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                        firstVisibleMessageId = firstVisibleKey,
                    ),
            )
        }.distinctUntilChanged()
            .collect { snapshot ->
                if (
                    MessageListVisibleAnchorScroll.shouldTrack(
                        isArmedOrPinned = snapshot.isArmedOrPinned,
                        isScrollInProgress = snapshot.isScrollInProgress,
                    )
                ) {
                    trackedAnchor.value = snapshot.anchor
                }
            }
    }

    LaunchedEffect(layoutAffectingRevision) {
        // `0` means content-only / no layout-affecting declared IDs — skip.
        if (layoutAffectingRevision == 0L || currentMessageIds.isEmpty()) {
            return@LaunchedEffect
        }

        runCatching {
            Log.d(
                LAYOUT_AFFECTING_LOG_TAG,
                "layoutAffecting unarmed-anchor rev=$layoutAffectingRevision " +
                    "count=${currentMessageIds.size}",
            )
        }

        val messageCount = currentMessageIds.size
        val armedOrPinnedBefore =
            autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled = currentAutoFollowEnabled) ||
                listState.isPinnedToTimelineEnd(
                    messageCount = messageCount,
                    tolerancePx = autoFollowTolerancePx,
                    bottomAnchorHeightPx = bottomAnchorHeightPx,
                )

        // Freeze the pre-apply unarmed anchor before awaiting layout.
        val frozenAnchor =
            if (
                MessageListVisibleAnchorScroll.shouldCapture(
                    hasLayoutAffectingChange = true,
                    isArmedOrPinned = armedOrPinnedBefore,
                    isScrollInProgress = listState.isScrollInProgress,
                )
            ) {
                trackedAnchor.value
            } else {
                null
            }

        withFrameNanos { }
        withFrameNanos { }

        // Skip restore when armed follow will settle to end after layout.
        val armedOrPinnedAfter =
            autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled = currentAutoFollowEnabled) ||
                listState.isPinnedToTimelineEnd(
                    messageCount = messageCount,
                    tolerancePx = autoFollowTolerancePx,
                    bottomAnchorHeightPx = bottomAnchorHeightPx,
                )

        if (
            !MessageListVisibleAnchorScroll.shouldRestore(
                anchor = frozenAnchor,
                isArmedOrPinned = armedOrPinnedAfter,
                isScrollInProgress = listState.isScrollInProgress,
            )
        ) {
            return@LaunchedEffect
        }

        val target =
            MessageListVisibleAnchorScroll.restoreTarget(
                anchor = checkNotNull(frozenAnchor),
                messageIdsChronological = currentMessageIds,
                // Bottom anchor plus an optional status row sit below the newest message.
                leadingItemCount =
                    (listState.layoutInfo.totalItemsCount - currentMessageIds.size).coerceAtLeast(1),
            ) ?: return@LaunchedEffect

        listState.scrollToItem(
            index = target.lazyIndex,
            scrollOffset = target.scrollOffset,
        )
    }
}

private data class UnarmedVisibleAnchorSnapshot(
    val isArmedOrPinned: Boolean,
    val isScrollInProgress: Boolean,
    val anchor: MessageListVisibleAnchor?,
)

@Composable
internal fun MessageListViewportResizeFollowEffect(
    listState: LazyListState,
    autoFollowEnabled: Boolean,
    autoFollowState: MessageListAutoFollowState,
    onFollowLayoutChange: suspend () -> Unit,
) {
    val currentAutoFollowEnabled by rememberUpdatedState(autoFollowEnabled)

    LaunchedEffect(listState, autoFollowState) {
        var lastViewportHeightPx = -1
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .collect { viewportHeightPx ->
                if (lastViewportHeightPx == -1) {
                    lastViewportHeightPx = viewportHeightPx
                    return@collect
                }
                if (viewportHeightPx == lastViewportHeightPx) {
                    return@collect
                }
                lastViewportHeightPx = viewportHeightPx
                if (autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled = currentAutoFollowEnabled)) {
                    withFrameNanos { }
                    withFrameNanos { }
                    onFollowLayoutChange()
                }
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
                scrollController.snapToEndAfterLayoutChange(
                    messageCount = currentMessageCount,
                    tolerancePx = autoFollowTolerancePx,
                    bottomAnchorHeightPx = bottomAnchorHeightPx,
                    autoFollowEnabled = currentAutoFollowEnabled,
                )
            }
        }
    }
}

internal suspend fun followMessageListLayoutChangeIfNeeded(
    listState: LazyListState,
    messageCount: Int,
    autoFollowEnabled: Boolean,
    autoFollowTolerancePx: Int,
    bottomAnchorHeightPx: Int,
    autoFollowState: MessageListAutoFollowState,
    scrollController: MessageListTimelineScrollController,
) {
    withFrameNanos { }
    withFrameNanos { }

    val pinned =
        listState.isPinnedToTimelineEnd(
            messageCount = messageCount,
            tolerancePx = autoFollowTolerancePx,
            bottomAnchorHeightPx = bottomAnchorHeightPx,
        )

    when {
        listState.isScrollInProgress ->
            autoFollowState.updatePinnedState(
                isPinnedToEnd = pinned,
                messageCount = messageCount,
            )

        autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled = autoFollowEnabled) ->
            scrollController.snapToEndAfterLayoutChange(
                messageCount = messageCount,
                tolerancePx = autoFollowTolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
                autoFollowEnabled = autoFollowEnabled,
            )

        else ->
            autoFollowState.updatePinnedState(
                isPinnedToEnd = pinned,
                messageCount = messageCount,
            )
    }
}

private data class MessageListAutoFollowLayoutSnapshot(
    val isPinnedToEnd: Boolean,
    val isScrollInProgress: Boolean,
    val viewportEndOffset: Int,
    val bottomAnchorOffset: Int?,
    val bottomAnchorSize: Int?,
)
