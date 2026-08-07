package dev.opencode.mobile.ui.scroll

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeActionPill
import dev.opencode.mobile.ui.components.OpenCodeActionPillVariant

internal object MessageListTimelineDefaults {
    val bottomAnchorHeight: Dp = 1.dp
    val autoFollowTolerance: Dp = 32.dp
    val revealThresholdMin: Dp = 120.dp
    val messageSpacing: Dp = 8.dp
    val scrollToEndPillBottomPadding: Dp = 12.dp

    val contentPadding =
        PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 12.dp,
        )
}

@Stable
internal class MessageListTimelineHandle(
    val listState: LazyListState,
    val showScrollToEnd: Boolean,
    val isPinnedToEnd: Boolean,
    val scrollController: MessageListTimelineScrollController,
    val onLastMessageContentSizeSettled: () -> Unit,
    val autoFollowTolerancePx: Int,
    val bottomAnchorHeightPx: Int,
    val messageCount: Int,
)

@Composable
internal fun rememberMessageListTimeline(
    messageCount: Int,
    firstMessageId: String?,
    lastMessageId: String?,
    layoutAffectingRevision: Long,
    autoFollowEnabled: Boolean,
    messageIdsChronological: List<String> = emptyList(),
    structureAffectingRevision: Long = layoutAffectingRevision,
    growBottomRevision: Long = 0L,
    listState: LazyListState = rememberLazyListState(),
): MessageListTimelineHandle {
    val density = LocalDensity.current
    val revealThresholdMinPx =
        with(density) { MessageListTimelineDefaults.revealThresholdMin.roundToPx() }
    val autoFollowTolerancePx =
        with(density) { MessageListTimelineDefaults.autoFollowTolerance.roundToPx() }
    val bottomAnchorHeightPx =
        with(density) { MessageListTimelineDefaults.bottomAnchorHeight.roundToPx() }
    // Unarmed restore needs real chronological IDs; gallery / count-only callers skip.
    val resolvedMessageIds =
        messageIdsChronological.takeIf { it.size == messageCount }.orEmpty()

    val isPinnedToEnd by remember(
        listState,
        messageCount,
        autoFollowTolerancePx,
        bottomAnchorHeightPx,
    ) {
        derivedStateOf {
            listState.isPinnedToTimelineEnd(
                messageCount = messageCount,
                tolerancePx = autoFollowTolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
            )
        }
    }
    val showScrollToEnd =
        rememberMessageListScrollToEndVisibility(
            listState = listState,
            messageCount = messageCount,
            revealThresholdMinPx = revealThresholdMinPx,
        )
    val autoFollowState =
        remember { MessageListAutoFollowState(initialMessageCount = messageCount) }
    val scrollController =
        rememberMessageListTimelineScrollController(
            listState = listState,
            autoFollowState = autoFollowState,
        )
    val onLastMessageContentSizeSettled =
        rememberMessageListLastMessageContentSizeSettled(
            autoFollowEnabled = autoFollowEnabled,
            messageCount = messageCount,
            autoFollowTolerancePx = autoFollowTolerancePx,
            bottomAnchorHeightPx = bottomAnchorHeightPx,
            autoFollowState = autoFollowState,
            scrollController = scrollController,
        )

    MessageListInitialEndSnapEffect(
        firstMessageId = firstMessageId,
        lastMessageId = lastMessageId,
        messageCount = messageCount,
        autoFollowEnabled = autoFollowEnabled,
        autoFollowTolerancePx = autoFollowTolerancePx,
        bottomAnchorHeightPx = bottomAnchorHeightPx,
        scrollController = scrollController,
    )

    MessageListAutoFollowArmingEffect(
        listState = listState,
        messageCount = messageCount,
        autoFollowEnabled = autoFollowEnabled,
        autoFollowTolerancePx = autoFollowTolerancePx,
        bottomAnchorHeightPx = bottomAnchorHeightPx,
        isPinnedToEnd = isPinnedToEnd,
        autoFollowState = autoFollowState,
        scrollController = scrollController,
    )

    MessageListViewportResizeFollowEffect(
        listState = listState,
        autoFollowEnabled = autoFollowEnabled,
        autoFollowState = autoFollowState,
        onFollowLayoutChange = {
            followMessageListLayoutChangeIfNeeded(
                listState = listState,
                messageCount = messageCount,
                autoFollowEnabled = autoFollowEnabled,
                autoFollowTolerancePx = autoFollowTolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
                autoFollowState = autoFollowState,
                scrollController = scrollController,
            )
        },
    )

    // Armed: declared-ID structure follow → await layout → exact end.
    // Decorate-only (TextMate / slash collapse) uses layoutAffectingRevision for
    // unarmed restore only — not armed re-scroll.
    MessageListAutoFollowLayoutAffectingEffect(
        layoutAffectingRevision = structureAffectingRevision,
        messageCount = messageCount,
        autoFollowEnabled = autoFollowEnabled,
        listState = listState,
        autoFollowTolerancePx = autoFollowTolerancePx,
        bottomAnchorHeightPx = bottomAnchorHeightPx,
        autoFollowState = autoFollowState,
        scrollController = scrollController,
    )

    // Armed only: streaming bottom growth without layoutAffecting (no unarmed restore).
    MessageListAutoFollowGrowBottomEffect(
        growBottomRevision = growBottomRevision,
        messageCount = messageCount,
        autoFollowEnabled = autoFollowEnabled,
        listState = listState,
        autoFollowTolerancePx = autoFollowTolerancePx,
        bottomAnchorHeightPx = bottomAnchorHeightPx,
        autoFollowState = autoFollowState,
        scrollController = scrollController,
    )

    // Unarmed: capture visible anchor ID+offset → restore after layout settles.
    MessageListUnarmedVisibleAnchorEffect(
        layoutAffectingRevision = layoutAffectingRevision,
        messageIdsChronological = resolvedMessageIds,
        autoFollowEnabled = autoFollowEnabled,
        listState = listState,
        autoFollowTolerancePx = autoFollowTolerancePx,
        bottomAnchorHeightPx = bottomAnchorHeightPx,
        autoFollowState = autoFollowState,
    )

    MessageListAutoFollowAppendEffect(
        messageCount = messageCount,
        autoFollowEnabled = autoFollowEnabled,
        autoFollowTolerancePx = autoFollowTolerancePx,
        bottomAnchorHeightPx = bottomAnchorHeightPx,
        autoFollowState = autoFollowState,
        scrollController = scrollController,
    )

    return MessageListTimelineHandle(
        listState = listState,
        showScrollToEnd = showScrollToEnd,
        isPinnedToEnd = isPinnedToEnd,
        scrollController = scrollController,
        onLastMessageContentSizeSettled = onLastMessageContentSizeSettled,
        autoFollowTolerancePx = autoFollowTolerancePx,
        bottomAnchorHeightPx = bottomAnchorHeightPx,
        messageCount = messageCount,
    )
}

@Composable
internal fun <T> MessageListReversedTimelineColumn(
    timeline: MessageListTimelineHandle,
    items: List<T>,
    bottomAnchorKey: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = MessageListTimelineDefaults.contentPadding,
    key: (T) -> Any,
    workingIndicatorKey: String? = null,
    workingIndicatorContent: (@Composable () -> Unit)? = null,
    itemContent:
        @Composable (
            item: T,
            isNewestMessage: Boolean,
            onContentSizeSettled: (() -> Unit)?,
        ) -> Unit,
) {
    val bottomAnchorHeight = MessageListTimelineDefaults.bottomAnchorHeight

    LazyColumn(
        state = timeline.listState,
        modifier = modifier,
        reverseLayout = true,
        contentPadding = contentPadding,
    ) {
        item(key = bottomAnchorKey) {
            Spacer(modifier = Modifier.height(bottomAnchorHeight))
        }
        if (workingIndicatorKey != null && workingIndicatorContent != null) {
            item(key = workingIndicatorKey) {
                workingIndicatorContent()
            }
        }
        itemsIndexed(
            items = items.asReversed(),
            key = { _, item -> key(item) },
        ) { reversedIndex, item ->
            val isNewestMessage = reversedIndex == 0 && workingIndicatorContent == null
            itemContent(
                item,
                isNewestMessage,
                if (isNewestMessage) timeline.onLastMessageContentSizeSettled else null,
            )
        }
    }
}

internal fun Modifier.messageListTimelineItemSpacing(isNewestMessage: Boolean): Modifier =
    padding(bottom = if (isNewestMessage) 0.dp else MessageListTimelineDefaults.messageSpacing)

@Composable
internal fun MessageListScrollToEndOverlay(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    applyBottomPadding: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier =
            modifier.padding(
                bottom =
                    if (applyBottomPadding) {
                        MessageListTimelineDefaults.scrollToEndPillBottomPadding
                    } else {
                        0.dp
                    },
            ),
    ) {
        OpenCodeActionPill(
            variant = OpenCodeActionPillVariant.ScrollToEnd,
            onClick = onClick,
        )
    }
}
