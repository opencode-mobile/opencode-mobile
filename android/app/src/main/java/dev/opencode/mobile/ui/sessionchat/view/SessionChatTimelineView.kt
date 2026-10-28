package dev.opencode.mobile.ui.sessionchat.view

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageStopReason

import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeActionPill
import dev.opencode.mobile.ui.components.OpenCodeActionPillVariant
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeAssistantMessage
import dev.opencode.mobile.ui.components.OpenCodeAssistantMessagePresentation
import dev.opencode.mobile.ui.components.OpenCodeMessageAttachment
import dev.opencode.mobile.ui.components.OpenCodeMessageNote
import dev.opencode.mobile.ui.components.OpenCodeMessageNoteVariant
import dev.opencode.mobile.ui.components.OpenCodeChatActivityState
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessageContent
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchPill
import dev.opencode.mobile.ui.components.OpenCodeSessionTodosPill
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeActivityAgentRow
import dev.opencode.mobile.ui.components.OpenCodeActivityCard
import dev.opencode.mobile.ui.components.OpenCodeActivityCardState
import dev.opencode.mobile.ui.components.OpenCodeActivityCurrentRow
import dev.opencode.mobile.ui.components.OpenCodeActivityIcon
import dev.opencode.mobile.ui.components.OpenCodeActivitySheet
import dev.opencode.mobile.ui.components.OpenCodeActivitySheetAgent
import dev.opencode.mobile.ui.components.OpenCodeActivitySheetDetail
import dev.opencode.mobile.ui.components.OpenCodeActivitySheetStep
import dev.opencode.mobile.ui.components.OpenCodeActivitySheetSubStep
import dev.opencode.mobile.ui.components.OpenCodeActivityStatus
import dev.opencode.mobile.ui.components.OpenCodeStepRail
import dev.opencode.mobile.ui.components.OpenCodeStepRailItem
import dev.opencode.mobile.ui.components.OpenCodeStepRailStatus
import dev.opencode.mobile.ui.components.OpenCodeSyncingPill
import dev.opencode.mobile.ui.components.OpenCodeTimelineNotice
import dev.opencode.mobile.ui.components.OpenCodeTimelineNoticeState
import dev.opencode.mobile.ui.components.OpenCodeTimelineNoticeVariant
import dev.opencode.mobile.ui.components.OpenCodeToolCallCard
import dev.opencode.mobile.ui.components.OpenCodeToolCallCardStatus
import dev.opencode.mobile.ui.components.OpenCodeToolChip
import dev.opencode.mobile.ui.components.OpenCodeToolChipStatus
import dev.opencode.mobile.ui.components.OpenCodeToolChipVariant
import dev.opencode.mobile.ui.components.OpenCodeReasoningRow
import dev.opencode.mobile.ui.components.OpenCodeWorkingIndicator
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.scroll.MessageListOlderHistory
import dev.opencode.mobile.ui.scroll.MessageListOlderLoadIntent
import dev.opencode.mobile.ui.scroll.MessageListReversedTimelineColumn
import dev.opencode.mobile.ui.scroll.MessageListTimelineApplyHold
import dev.opencode.mobile.ui.scroll.MessageListTimelineDefaults
import dev.opencode.mobile.ui.scroll.messageListAnimateLastMessageSize
import dev.opencode.mobile.ui.scroll.messageListTimelineItemSpacing
import dev.opencode.mobile.ui.scroll.rememberMessageListTimeline
import dev.opencode.mobile.ui.scroll.rememberMessageListUserScroll
import dev.opencode.mobile.ui.sessionchat.AttachmentPresentationMapper
import dev.opencode.mobile.ui.sessionchat.SessionBranchPillUiState
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatNoticeCopy
import dev.opencode.mobile.ui.sessionchat.SessionChatNoticeModelNames
import dev.opencode.mobile.ui.sessionchat.SessionChatActivityCard
import dev.opencode.mobile.ui.sessionchat.SessionChatActivityFooter
import dev.opencode.mobile.ui.sessionchat.SessionChatActivitySheetContent
import dev.opencode.mobile.ui.sessionchat.SessionChatChildActivity
import dev.opencode.mobile.ui.sessionchat.SessionChatStep
import dev.opencode.mobile.ui.sessionchat.SessionChatStepFamily
import dev.opencode.mobile.ui.sessionchat.SessionChatStepKind
import dev.opencode.mobile.ui.sessionchat.SessionChatStepRail
import dev.opencode.mobile.ui.sessionchat.SessionChatStepStatus
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
import dev.opencode.mobile.ui.sessionchat.ToolResultInspector
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.SessionChatTopPill
import dev.opencode.mobile.ui.sessionchat.StepParagraphChips
import dev.opencode.mobile.ui.sessionchat.TimelineApply
import dev.opencode.mobile.ui.sessionchat.TimelineFrame
import dev.opencode.mobile.ui.sessionchat.ToolChipKind
import dev.opencode.mobile.ui.sessionchat.WorkingTurnIndicator
import dev.opencode.mobile.ui.sessionchat.displayLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield

private val LocalHideLiveRetryFailures = staticCompositionLocalOf { false }
private val LocalRetryCountdownBlockId = staticCompositionLocalOf<String?> { null }
private val LocalNoticeModelNames = staticCompositionLocalOf { SessionChatNoticeModelNames() }

internal data class SessionChatTimelineViewProps(
    val timeline: SessionChatTimelineState,
    val messages: List<SessionChatMessageItem>,
    val timelineFrame: TimelineFrame? = null,
    val isRefreshing: Boolean,
    val hasOlder: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val errorMessage: String?,
    val sessionBranchPill: SessionBranchPillUiState? = null,
    val syncingIndicatorLabel: String? = null,
    val workingIndicatorLabel: String? = null,
    val workingIndicatorCollapsed: Boolean = false,
    val workingIndicatorFading: Boolean = false,
    val sessionTodosVisible: Boolean = false,
    val sessionTodoCompletedCount: Int = 0,
    val sessionTodoTotalCount: Int = 0,
    val sessionTodoActivePreview: String? = null,
    val hideFloatStack: Boolean = false,
    /** Hide rate-limit and quota marks while the composer retry status is showing. */
    val hideLiveRetryFailures: Boolean = false,
    /** The session is retrying: the composer owns the countdown, so failure marks show none. */
    val hideRetryCountdown: Boolean = false,
    /** Model display names a "Switched to" notice shows; the model id when absent. */
    val noticeModelNames: SessionChatNoticeModelNames = SessionChatNoticeModelNames(),
    /** Extra bottom inset so the timeline can scroll under a floating composer. */
    val bottomOverlayInset: Dp = 0.dp,
    val onRefresh: () -> Unit,
    val onRetry: () -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    val onSessionBranchClick: () -> Unit = {},
    val onSessionTodosClick: () -> Unit = {},
    val onMessageActionsTap: (SessionChatMessageActionsTarget) -> Unit,
    val onToolDetailTap: (SessionChatToolDetailTarget) -> Unit = {},
    val onToolGroupTap: (SessionChatToolGroupTarget) -> Unit = {},
    val onSkillsTap: (SessionChatSkillsTarget) -> Unit = {},
    val onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit = { _, _ -> },
    val onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit = { _, _ -> },
    val supportsSessionMessageDiff: Boolean = true,
    val supportsRevert: Boolean = true,
    /**
     * Chip-timeline paragraphs expanded past `+N more` (expansion id to chip
     * count at tap time); owned by the Session Chat screen model.
     */
    val expandedStepParagraphs: Map<String, Int> = emptyMap(),
    val onExpandStepParagraph: (expansionId: String, chipCount: Int) -> Unit = { _, _ -> },
    /** Step rails unfolded past `N earlier steps` (#779); owned by the Session Chat screen model. */
    val unfoldedStepRails: Set<String> = emptySet(),
    val onToggleStepRailFold: (railId: String) -> Unit = {},
    /** Activity card whose live sheet is open (#779); owned by the Session Chat screen model. */
    val presentedActivityCard: String? = null,
    /** What running child sessions on activity cards are doing, by child session id. */
    val activityCardChildren: Map<String, SessionChatChildActivity> = emptyMap(),
    val activityCardFooters: Map<String, SessionChatActivityFooter> = emptyMap(),
    val debouncedStepIds: Set<String> = emptySet(),
    val onOpenActivityCard: (blockId: String) -> Unit = {},
    val onDismissActivityCard: () -> Unit = {},
    /** Step of [presentedActivityCard] whose detail the sheet shows in place of its list. */
    val activityCardDetailStepId: String? = null,
    val onOpenActivityCardStep: (stepId: String) -> Unit = {},
    val onCloseActivityCardStep: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionChatTimelineView(
    props: SessionChatTimelineViewProps,
    modifier: Modifier = Modifier,
) {
    when (props.timeline) {
        SessionChatTimelineState.Creating ->
            OpenCodeStatusStack(
                title = "Starting session…",
                subtitle = "Creating on server…",
                testTag = "sessionChatCreating",
                modifier = modifier.fillMaxSize(),
            )

        SessionChatTimelineState.Loading ->
            OpenCodeStatusStack(
                title = "Loading messages",
                subtitle = "Fetching timeline…",
                testTag = "sessionChatLoading",
                modifier = modifier.fillMaxSize(),
            )

        SessionChatTimelineState.Error ->
            OpenCodeEmptyState(
                title = "Unable to load messages",
                message = props.errorMessage?.takeIf { it.isNotBlank() } ?: "The timeline didn't load.",
                primaryAction =
                    OpenCodeEmptyStateAction(title = "Retry") {
                        props.onRetry()
                    },
                modifier = modifier.fillMaxSize(),
            )

        SessionChatTimelineState.Empty ->
            Box(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No messages yet. Send a prompt to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        SessionChatTimelineState.Ready -> {
            val retryCountdownBlockId =
                remember(props.messages, props.hideRetryCountdown) {
                    if (props.hideRetryCountdown) null else SessionChatTimelineBlocks.retryCountdownBlockId(props.messages)
                }
            CompositionLocalProvider(
                LocalHideLiveRetryFailures provides props.hideLiveRetryFailures,
                LocalRetryCountdownBlockId provides retryCountdownBlockId,
                LocalNoticeModelNames provides props.noticeModelNames,
            ) {
            SessionChatTimelineReadyView(
                messages = props.messages,
                timelineFrame = props.timelineFrame,
                isRefreshing = props.isRefreshing,
                hasOlder = props.hasOlder,
                isLoadingOlder = props.isLoadingOlder,
                sessionBranchPill = props.sessionBranchPill,
                syncingIndicatorLabel = props.syncingIndicatorLabel,
                workingIndicatorLabel = props.workingIndicatorLabel,
                workingIndicatorCollapsed = props.workingIndicatorCollapsed,
                workingIndicatorFading = props.workingIndicatorFading,
                sessionTodosVisible = props.sessionTodosVisible,
                sessionTodoCompletedCount = props.sessionTodoCompletedCount,
                sessionTodoTotalCount = props.sessionTodoTotalCount,
                sessionTodoActivePreview = props.sessionTodoActivePreview,
                hideFloatStack = props.hideFloatStack,
                bottomOverlayInset = props.bottomOverlayInset,
                onRefresh = props.onRefresh,
                onLoadOlder = props.onLoadOlder,
                onSessionBranchClick = props.onSessionBranchClick,
                onSessionTodosClick = props.onSessionTodosClick,
                onMessageActionsTap = props.onMessageActionsTap,
                onToolDetailTap = props.onToolDetailTap,
                onToolGroupTap = props.onToolGroupTap,
                onSkillsTap = props.onSkillsTap,
                onChildSessionTap = props.onChildSessionTap,
                onChangesReviewTap = props.onChangesReviewTap,
                supportsSessionMessageDiff = props.supportsSessionMessageDiff,
                supportsRevert = props.supportsRevert,
                expandedStepParagraphs = props.expandedStepParagraphs,
                onExpandStepParagraph = props.onExpandStepParagraph,
                unfoldedStepRails = props.unfoldedStepRails,
                onToggleStepRailFold = props.onToggleStepRailFold,
                activityCardChildren = props.activityCardChildren,
                activityCardFooters = props.activityCardFooters,
                debouncedStepIds = props.debouncedStepIds,
                onOpenActivityCard = props.onOpenActivityCard,
                modifier = modifier,
            )
            ActivityCardSheet(props = props)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionChatTimelineReadyView(
    messages: List<SessionChatMessageItem>,
    timelineFrame: TimelineFrame?,
    isRefreshing: Boolean,
    hasOlder: Boolean,
    isLoadingOlder: Boolean,
    sessionBranchPill: SessionBranchPillUiState?,
    syncingIndicatorLabel: String?,
    workingIndicatorLabel: String?,
    workingIndicatorCollapsed: Boolean,
    workingIndicatorFading: Boolean,
    sessionTodosVisible: Boolean,
    sessionTodoCompletedCount: Int,
    sessionTodoTotalCount: Int,
    sessionTodoActivePreview: String?,
    hideFloatStack: Boolean,
    bottomOverlayInset: Dp,
    onRefresh: () -> Unit,
    onLoadOlder: () -> Unit,
    onSessionBranchClick: () -> Unit,
    onSessionTodosClick: () -> Unit,
    onMessageActionsTap: (SessionChatMessageActionsTarget) -> Unit,
    onToolDetailTap: (SessionChatToolDetailTarget) -> Unit,
    onToolGroupTap: (SessionChatToolGroupTarget) -> Unit,
    onSkillsTap: (SessionChatSkillsTarget) -> Unit,
    onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit,
    onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit,
    supportsSessionMessageDiff: Boolean,
    supportsRevert: Boolean,
    expandedStepParagraphs: Map<String, Int>,
    onExpandStepParagraph: (expansionId: String, chipCount: Int) -> Unit,
    unfoldedStepRails: Set<String>,
    onToggleStepRailFold: (railId: String) -> Unit,
    activityCardChildren: Map<String, SessionChatChildActivity>,
    activityCardFooters: Map<String, SessionChatActivityFooter>,
    debouncedStepIds: Set<String>,
    onOpenActivityCard: (blockId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var topOverlayInsetPx by remember { mutableIntStateOf(0) }
    val topOverlayInset = with(density) { topOverlayInsetPx.toDp() }
    // Only user drag/fling holds applies — not programmatic auto-follow
    // (listState.isScrollInProgress is true for both; iOS holds on isDragging /
    // isDecelerating / isUserScrolling only).
    val userScroll = rememberMessageListUserScroll(listState)
    val isUserScrollInProgress = userScroll.inProgress

    // Host-side latest-wins hold under user drag/fling (iOS pendingApply parity).
    // Required frames flush immediately; other kinds keep the committed snapshot.
    var committedMessages by remember { mutableStateOf(messages) }
    var committedFrame by remember { mutableStateOf(timelineFrame) }
    var committedWorkingLabel by remember { mutableStateOf(workingIndicatorLabel) }
    var committedWorkingCollapsed by remember { mutableStateOf(workingIndicatorCollapsed) }
    var committedWorkingFading by remember { mutableStateOf(workingIndicatorFading) }
    LaunchedEffect(
        messages,
        timelineFrame,
        workingIndicatorLabel,
        workingIndicatorCollapsed,
        workingIndicatorFading,
        isUserScrollInProgress,
    ) {
        val hold =
            MessageListTimelineApplyHold.shouldHold(
                isUserScrollInProgress = isUserScrollInProgress,
                isRequired = timelineFrame?.isRequired == true,
            )
        if (!hold) {
            committedMessages = messages
            committedFrame = timelineFrame
            committedWorkingLabel = workingIndicatorLabel
            committedWorkingCollapsed = workingIndicatorCollapsed
            committedWorkingFading = workingIndicatorFading
        } else {
            runCatching {
                Log.d(
                    "SessionChatScroll",
                    "timelineApplyHold userScroll=true required=false " +
                        "incomingCount=${messages.size} committedCount=${committedMessages.size}",
                )
            }
        }
    }

    val apply = committedFrame?.let(TimelineApply::classify)
    val layoutAffectingRevision = apply?.layoutAffectingRevision ?: 0L
    val structureAffectingRevision = apply?.structureAffectingRevision ?: 0L
    val growBottomRevision = apply?.growBottomRevision ?: 0L
    // List keys, not row ids: visible-anchor restore matches them against item keys.
    val messageIds =
        remember(committedMessages, committedFrame) {
            committedMessages.map { committedFrame?.listKey(it.id) ?: it.id }
        }
    val timeline =
        rememberMessageListTimeline(
            messageCount = committedMessages.size,
            layoutAffectingRevision = layoutAffectingRevision,
            structureAffectingRevision = structureAffectingRevision,
            growBottomRevision = growBottomRevision,
            autoFollowEnabled = true,
            messageIdsChronological = messageIds,
            listState = listState,
            userScroll = userScroll,
        )

    val oldestMessageId = committedMessages.firstOrNull()?.id
    val oldestMessageKey = oldestMessageId?.let { committedFrame?.listKey(it) ?: it }
    val oldestVisible by remember(listState, oldestMessageKey) {
        derivedStateOf {
            oldestMessageId != null &&
                listState.layoutInfo.visibleItemsInfo.any { it.key == oldestMessageKey }
        }
    }
    val olderLoadIntent = remember { MessageListOlderLoadIntent() }
    var lastVisibleIndex by remember { mutableIntStateOf(listState.firstVisibleItemIndex) }
    var lastVisibleOffset by remember { mutableIntStateOf(listState.firstVisibleItemScrollOffset) }
    var olderLoadArmed by remember { mutableStateOf(false) }
    val latestOnLoadOlder by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        withFrameNanos { }
        olderLoadIntent.markPresentationSettled()
    }
    LaunchedEffect(listState, userScroll.inProgress, oldestVisible, hasOlder, isLoadingOlder) {
        snapshotFlow {
            Triple(
                userScroll.inProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect { (inProgress, index, offset) ->
            val towardOldest =
                MessageListOlderHistory.isTowardOldest(
                    previousIndex = lastVisibleIndex,
                    previousOffset = lastVisibleOffset,
                    index = index,
                    offset = offset,
                )
            lastVisibleIndex = index
            lastVisibleOffset = offset
            olderLoadIntent.onUserScroll(
                inProgress = inProgress,
                towardOldest = towardOldest,
            )
            olderLoadArmed =
                olderLoadIntent.shouldLoadOlder(
                    hasOlder = hasOlder,
                    isLoadingOlder = isLoadingOlder,
                    oldestVisible = oldestVisible,
                )
        }
    }
    LaunchedEffect(oldestVisible, hasOlder, isLoadingOlder, olderLoadArmed) {
        if (
            olderLoadIntent.shouldLoadOlder(
                hasOlder = hasOlder,
                isLoadingOlder = isLoadingOlder,
                oldestVisible = oldestVisible,
            )
        ) {
            olderLoadIntent.consume()
            olderLoadArmed = false
            latestOnLoadOlder()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            MessageListReversedTimelineColumn(
                timeline = timeline,
                items = committedMessages,
                bottomAnchorKey = SESSION_CHAT_BOTTOM_ANCHOR_KEY,
                modifier =
                    Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top =
                            MessageListTimelineDefaults.contentPadding.calculateTopPadding() +
                                topOverlayInset,
                        // reverseLayout: bottom padding is beforeContent (visual bottom).
                        // Overlay inset is composer height + 16 end-pin gap — do not also
                        // add the default 12 or the resting gap becomes 28.
                        bottom = bottomOverlayInset,
                    ),
                // A server echo keeps the item slot of the optimistic row it replaced.
                key = { message -> committedFrame?.listKey(message.id) ?: message.id },
                statusIndicatorKey =
                    if (committedWorkingLabel != null) {
                        SESSION_CHAT_STATUS_INDICATOR_KEY
                    } else {
                        null
                    },
                statusIndicatorCollapsed = committedWorkingCollapsed,
                statusIndicatorContent =
                    committedWorkingLabel?.let { label ->
                        {
                            OpenCodeWorkingIndicator(
                                label = label,
                                state =
                                    when {
                                        committedWorkingCollapsed -> OpenCodeChatActivityState.Collapsed
                                        WorkingTurnIndicator.showsIdleMark(label) -> OpenCodeChatActivityState.Idle
                                        else -> OpenCodeChatActivityState.Working
                                    },
                                fading = committedWorkingFading,
                            )
                        }
                    },
            ) { message, isNewestMessage, onContentSizeSettled ->
                SessionChatMessageRow(
                    message = message,
                    onMessageActionsTap = onMessageActionsTap,
                    onToolDetailTap = onToolDetailTap,
                    onToolGroupTap = onToolGroupTap,
                    onSkillsTap = onSkillsTap,
                    onChildSessionTap = onChildSessionTap,
                    onChangesReviewTap = onChangesReviewTap,
                    supportsSessionMessageDiff = supportsSessionMessageDiff,
                    supportsRevert = supportsRevert,
                    expandedStepParagraphs = expandedStepParagraphs,
                    onExpandStepParagraph = onExpandStepParagraph,
                    unfoldedStepRails = unfoldedStepRails,
                    onToggleStepRailFold = onToggleStepRailFold,
                    activityCardChildren = activityCardChildren,
                    activityCardFooters = activityCardFooters,
                    debouncedStepIds = debouncedStepIds,
                    onOpenActivityCard = onOpenActivityCard,
                    // Streaming height growth is GrowBottom + unanimated layout; do not
                    // animateContentSize on production Session Chat (gallery may still demo it).
                    animateSizeChanges = false,
                    onContentSizeAnimationFinished = onContentSizeSettled,
                    modifier = Modifier.messageListTimelineItemSpacing(isNewestMessage),
                )
            }
        }

        val showScrollToEnd = !hideFloatStack && timeline.showScrollToEnd
        val displayedSyncingLabel = rememberDisplayedSyncingLabel(syncingIndicatorLabel)
        val topPill =
            SessionChatTopPill.resolve(
                syncingLabel = displayedSyncingLabel,
                todosVisible = sessionTodosVisible,
                todoTotalCount = sessionTodoTotalCount,
                branchPill = sessionBranchPill,
                hideFloatStack = hideFloatStack,
                todoCompletedCount = sessionTodoCompletedCount,
                todoActivePreview = sessionTodoActivePreview,
            )
        val showTopOverlayStack = topPill != null
        LaunchedEffect(showTopOverlayStack) {
            if (!showTopOverlayStack) {
                topOverlayInsetPx = 0
            }
        }
        if (topPill != null) {
            AnimatedContent(
                targetState = topPill,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .onGloballyPositioned { coordinates ->
                            val height = coordinates.size.height
                            if (height != topOverlayInsetPx) {
                                topOverlayInsetPx = height
                            }
                        }.padding(top = 10.dp),
                transitionSpec = {
                    fadeIn(tween(durationMillis = 200)) togetherWith fadeOut(tween(durationMillis = 200))
                },
                contentAlignment = Alignment.Center,
                label = "sessionChatTopPill",
                // Swap only between pill kinds; label or count updates stay in place.
                contentKey = { it::class },
            ) { pill ->
                Box(contentAlignment = Alignment.Center) {
                    when (pill) {
                        is SessionChatTopPill.Syncing -> OpenCodeSyncingPill(label = pill.label)
                        is SessionChatTopPill.Todos ->
                            OpenCodeSessionTodosPill(
                                completedCount = pill.completedCount,
                                totalCount = pill.totalCount,
                                activePreview = pill.activePreview,
                                onClick = onSessionTodosClick,
                            )
                        is SessionChatTopPill.Branch ->
                            OpenCodeSessionBranchPill(
                                branch = pill.pill.branch,
                                additions = pill.pill.additions,
                                deletions = pill.pill.deletions,
                                onClick = onSessionBranchClick,
                            )
                    }
                }
            }
        }

        if (showScrollToEnd) {
            OpenCodeActionPill(
                variant = OpenCodeActionPillVariant.ScrollToEnd,
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SESSION_CHAT,
                        actionId = ProductAnalytics.Action.CHAT_SCROLL_TO_END,
                    )
                    timeline.scrollController.animateToEnd(
                        messageCount = timeline.messageCount,
                        tolerancePx = timeline.autoFollowTolerancePx,
                        bottomAnchorHeightPx = timeline.bottomAnchorHeightPx,
                        autoFollowEnabled = true,
                    )
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomOverlayInset),
            )
        }
    }
}

@Composable
private fun SessionChatMessageRow(
    message: SessionChatMessageItem,
    onMessageActionsTap: (SessionChatMessageActionsTarget) -> Unit,
    onToolDetailTap: (SessionChatToolDetailTarget) -> Unit,
    onToolGroupTap: (SessionChatToolGroupTarget) -> Unit,
    onSkillsTap: (SessionChatSkillsTarget) -> Unit,
    onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit,
    onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit,
    supportsSessionMessageDiff: Boolean,
    supportsRevert: Boolean,
    expandedStepParagraphs: Map<String, Int>,
    onExpandStepParagraph: (expansionId: String, chipCount: Int) -> Unit,
    unfoldedStepRails: Set<String>,
    onToggleStepRailFold: (railId: String) -> Unit,
    activityCardChildren: Map<String, SessionChatChildActivity>,
    activityCardFooters: Map<String, SessionChatActivityFooter>,
    debouncedStepIds: Set<String>,
    onOpenActivityCard: (blockId: String) -> Unit,
    animateSizeChanges: Boolean,
    onContentSizeAnimationFinished: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    fun openMessageActions(showsRevertMessage: Boolean) {
        onMessageActionsTap(
            SessionChatMessageActionsTarget(
                id = message.id,
                text = message.text,
                showsRevertMessage = showsRevertMessage,
            ),
        )
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .messageListAnimateLastMessageSize(
                    animateSizeChanges = animateSizeChanges,
                    onContentSizeAnimationFinished = onContentSizeAnimationFinished,
                ),
        horizontalAlignment =
            if (message.role == MessageRole.User) {
                Alignment.End
            } else {
                Alignment.Start
            },
    ) {
        when (message.role) {
            MessageRole.User ->
                OpenCodeOutgoingMessage(
                    text = message.text,
                    renderedMarkdown = message.renderedMarkdown,
                    metadataLabel = message.metadataLabel,
                    content = message.outgoingContent(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { openMessageActions(showsRevertMessage = supportsRevert) },
                )

            MessageRole.Assistant ->
                AssistantMessageContent(
                    message = message,
                    onOpenMessageActions = { openMessageActions(showsRevertMessage = false) },
                    onToolDetailTap = onToolDetailTap,
                    onToolGroupTap = onToolGroupTap,
                    onSkillsTap = onSkillsTap,
                    onChildSessionTap = onChildSessionTap,
                    onChangesReviewTap = onChangesReviewTap,
                    supportsSessionMessageDiff = supportsSessionMessageDiff,
                    expandedStepParagraphs = expandedStepParagraphs,
                    onExpandStepParagraph = onExpandStepParagraph,
                    unfoldedStepRails = unfoldedStepRails,
                    onToggleStepRailFold = onToggleStepRailFold,
                    activityCardChildren = activityCardChildren,
                    activityCardFooters = activityCardFooters,
                    debouncedStepIds = debouncedStepIds,
                    onOpenActivityCard = onOpenActivityCard,
                )

            MessageRole.Notice ->
                message.blocks.forEach { block ->
                    if (block is SessionChatTimelineBlock.Notice) {
                        key(block.blockID) { TimelineNoticeBlock(block) }
                    }
                }

            MessageRole.Command, MessageRole.Tool, MessageRole.Unknown ->
                FallbackMessageBubble(
                    roleLabel = message.role.displayLabel(),
                    text = message.text,
                )
        }
    }
}

private fun SessionChatMessageItem.outgoingContent(): List<OpenCodeOutgoingMessageContent> {
    val textBlockCount = blocks.count { it is SessionChatTimelineBlock.Text }
    return blocks.mapNotNull { block ->
        when (block) {
            is SessionChatTimelineBlock.Text ->
                OpenCodeOutgoingMessageContent.Text(
                    text = block.text,
                    renderedMarkdown =
                        block.renderedMarkdown
                            ?: renderedMarkdown.takeIf { textBlockCount == 1 },
                )
            is SessionChatTimelineBlock.Attachment ->
                OpenCodeOutgoingMessageContent.Attachment(AttachmentPresentationMapper.from(block.attachment))
            is SessionChatTimelineBlock.ToolCard, is SessionChatTimelineBlock.ToolGroup,
            is SessionChatTimelineBlock.ChildSessionRow,
            is SessionChatTimelineBlock.Reasoning,
            is SessionChatTimelineBlock.StepParagraph,
            is SessionChatTimelineBlock.StepRail,
            is SessionChatTimelineBlock.ActivityCard,
            is SessionChatTimelineBlock.StopNote,
            is SessionChatTimelineBlock.FailureLine,
            is SessionChatTimelineBlock.Notice,
            -> null
        }
    }
}

@Composable
private fun AssistantMessageContent(
    message: SessionChatMessageItem,
    onOpenMessageActions: () -> Unit,
    onToolDetailTap: (SessionChatToolDetailTarget) -> Unit,
    onToolGroupTap: (SessionChatToolGroupTarget) -> Unit,
    onSkillsTap: (SessionChatSkillsTarget) -> Unit,
    onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit,
    onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit,
    supportsSessionMessageDiff: Boolean,
    expandedStepParagraphs: Map<String, Int>,
    onExpandStepParagraph: (expansionId: String, chipCount: Int) -> Unit,
    unfoldedStepRails: Set<String>,
    onToggleStepRailFold: (railId: String) -> Unit,
    activityCardChildren: Map<String, SessionChatChildActivity>,
    activityCardFooters: Map<String, SessionChatActivityFooter>,
    debouncedStepIds: Set<String>,
    onOpenActivityCard: (blockId: String) -> Unit,
) {
    // Rows never render empty — unpaintable blocks are filtered and an empty
    // result falls back to the live placeholder.
    val blocks = SessionChatTimelineBlocks.paintedBlocks(message)
    val streamingStepParagraphBlockId =
        SessionChatTimelineBlocks.streamingStepParagraphBlockId(blocks, message.isStreaming)
    // A sole narration carrier (text block or step paragraph with text) takes
    // the message-scoped markdown, as before the chip timeline.
    val textBlockCount = blocks.count { it.markdownText != null }

    val toolTaps =
        ToolTapTargets(
            messageId = message.id,
            supportsSessionMessageDiff = supportsSessionMessageDiff,
            onChildSessionTap = onChildSessionTap,
            onChangesReviewTap = onChangesReviewTap,
            onToolDetailTap = onToolDetailTap,
        )

    fun toolCardAction(block: SessionChatTimelineBlock.ToolCard): (() -> Unit)? = toolTaps.toolCardAction(block)

    fun stepAction(step: SessionChatStep): (() -> Unit)? = toolTaps.stepAction(step)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        blocks.forEach { block ->
            // A step paragraph's block id grows with every tool; key on its
            // stable expansion id so a new chip updates the row in place.
            val rowKey = if (block is SessionChatTimelineBlock.StepParagraph) block.expansionID else block.blockID
            key(rowKey) {
                when (block) {
                    is SessionChatTimelineBlock.Text -> {
                        val renderedMarkdown =
                            block.renderedMarkdown
                                ?: message.renderedMarkdown.takeIf { textBlockCount == 1 }
                        AssistantMessageBubble(
                            text = block.text,
                            renderedMarkdown = renderedMarkdown,
                            isStreaming = message.isStreaming,
                            onTextTap = onOpenMessageActions,
                        )
                    }
                    is SessionChatTimelineBlock.Attachment ->
                        OpenCodeMessageAttachment(AttachmentPresentationMapper.from(block.attachment))
                    is SessionChatTimelineBlock.ToolCard ->
                        OpenCodeToolCallCard(
                            status = block.status.toToolCallCardStatus(interrupted = block.isInterrupted),
                            title = block.title,
                            subtitle = block.subtitle,
                            additions = block.additions,
                            deletions = block.deletions,
                            errorText = block.errorText,
                            onClick = toolCardAction(block),
                        )
                    is SessionChatTimelineBlock.ToolGroup ->
                        OpenCodeToolCallCard(
                            status = block.status.toToolCallCardStatus(),
                            title = block.title,
                            subtitle = block.subtitle,
                            errorText = block.errorText,
                            onClick = {
                                onToolGroupTap(
                                    SessionChatToolGroupTarget(
                                        blockID = block.blockID,
                                        title = block.title,
                                        subtitle = block.subtitle,
                                        members = block.members,
                                        messageId = message.id,
                                    ),
                                )
                            },
                        )
                    is SessionChatTimelineBlock.ChildSessionRow ->
                        SessionChatChildSessionRow(
                            agentName = block.agentName,
                            isBackground = block.isBackground,
                            secondLine = block.secondLine,
                            timeStartEpochMs = block.timeStartEpochMs,
                            onClick =
                                block.childSessionId?.let { childSessionId ->
                                    {
                                        onChildSessionTap(childSessionId, block.agentName)
                                    }
                                },
                        )
                    is SessionChatTimelineBlock.Reasoning ->
                        OpenCodeReasoningRow(
                            state = OpenCodeChatActivityState.Expanded,
                            durationLabel = SessionChatTimelineBlocks.thoughtDurationLabel(block.durationMs),
                            onClick =
                                block.text.takeIf { it.isNotBlank() }?.let { body ->
                                    {
                                        onToolDetailTap(
                                            thoughtDetailTarget(
                                                body,
                                                SessionChatTimelineBlocks.thoughtDurationLabel(block.durationMs),
                                            ),
                                        )
                                    }
                                },
                        )
                    is SessionChatTimelineBlock.StopNote ->
                        OpenCodeMessageNote(
                            variant =
                                when (block.reason) {
                                    MessageStopReason.Length -> OpenCodeMessageNoteVariant.Truncated
                                    MessageStopReason.Interrupted -> OpenCodeMessageNoteVariant.Interrupted
                                },
                        )
                    is SessionChatTimelineBlock.Notice -> TimelineNoticeBlock(block)
                    is SessionChatTimelineBlock.FailureLine -> {
                        if (!LocalHideLiveRetryFailures.current || !block.coveredByLiveRetry) {
                            FailureLineBlock(
                                block = block,
                                onToolDetailTap = onToolDetailTap,
                            )
                        }
                    }
                    is SessionChatTimelineBlock.StepParagraph ->
                        StepParagraphBlock(
                            block = block,
                            renderedMarkdown =
                                block.renderedMarkdown
                                    ?: message.renderedMarkdown.takeIf { textBlockCount == 1 && block.text != null },
                            isStreaming = block.blockID == streamingStepParagraphBlockId,
                            onOpenMessageActions = onOpenMessageActions,
                            chipAction = { chip ->
                                if (chip.kind == ToolChipKind.Skill) {
                                    { onSkillsTap(SessionChatSkillsTarget(SessionChatTimelineBlocks.skillUses(blocks))) }
                                } else {
                                    toolCardAction(chip.card)
                                }
                            },
                            exploreAction = { summary ->
                                onToolGroupTap(
                                    SessionChatToolGroupTarget(
                                        blockID = summary.blockID,
                                        title = summary.title,
                                        subtitle = summary.subtitle,
                                        members = summary.members,
                                        messageId = message.id,
                                    ),
                                )
                            },
                            expandedThrough = expandedStepParagraphs[block.expansionID],
                            onExpand = { onExpandStepParagraph(block.expansionID, block.chips.size) },
                        )
                    is SessionChatTimelineBlock.StepRail ->
                        StepRailBlock(
                            block = block,
                            unfolded = block.blockID in unfoldedStepRails,
                            debouncedStepIds = debouncedStepIds,
                            onToggleFold = { onToggleStepRailFold(block.blockID) },
                            stepAction = ::stepAction,
                        )
                    is SessionChatTimelineBlock.ActivityCard ->
                        ActivityCardBlock(
                            block = block,
                            children = activityCardChildren,
                            footer = activityCardFooters[block.blockID],
                            onOpen = { onOpenActivityCard(block.blockID) },
                            stepAction = ::stepAction,
                        )
                }
            }
        }
    }
}

/**
 * Step rail timeline (#779): folds the rail at render time and maps steps onto
 * [OpenCodeStepRail]. [stepAction] is the standard timeline's tap target for
 * the step's tool card (child session, changes review, detail sheet).
 */
@Composable
private fun StepRailBlock(
    block: SessionChatTimelineBlock.StepRail,
    unfolded: Boolean,
    debouncedStepIds: Set<String>,
    onToggleFold: () -> Unit,
    stepAction: (SessionChatStep) -> (() -> Unit)?,
) {
    // Steps seen in flight for under 300 ms are not drawn yet; Thinking stays until a drawn step runs.
    val visible = remember(block.steps, debouncedStepIds) { SessionChatStepRail.visibleSteps(block.steps, debouncedStepIds) }
    val entries = remember(visible, unfolded) { SessionChatStepRail.entries(visible, unfolded) }
    val actions = entries.mapNotNull { (it as? SessionChatStepRail.Entry.Step)?.step }.associate { it.id to stepAction(it) }
    val items = entries.map { entry -> entry.toStepRailItem(tappable = { id -> actions[id] != null }) }
    OpenCodeStepRail(
        steps = items,
        isLive = block.isLive,
        thinking = block.isLive && visible.none { it.status.isInFlight },
        onStepTap = { id -> actions[id]?.invoke() },
        onFoldTap = onToggleFold,
        onChildSessionTap = { id -> actions[id]?.invoke() },
    )
}

private fun SessionChatStepRail.Entry.toStepRailItem(tappable: (String) -> Boolean): OpenCodeStepRailItem =
    when (this) {
        is SessionChatStepRail.Entry.Fold -> OpenCodeStepRailItem.Fold(count = count, status = status.toStepRailStatus())
        is SessionChatStepRail.Entry.Step ->
            when (step.kind) {
                SessionChatStepKind.ChildSession ->
                    OpenCodeStepRailItem.ChildSession(
                        id = step.id,
                        agent = step.title,
                        description = step.childSession?.description,
                        status = step.status.toStepRailStatus(),
                        startedAtMs = step.startedAtMs,
                        endedAtMs = step.endedAtMs,
                        detail = step.detail,
                        tappable = tappable(step.id),
                    )
                SessionChatStepKind.Tool, SessionChatStepKind.Thought ->
                    OpenCodeStepRailItem.Step(
                        id = step.id,
                        title = step.title,
                        detail = step.detail,
                        status = step.status.toStepRailStatus(),
                        additions = step.additions,
                        deletions = step.deletions,
                        startedAtMs = step.startedAtMs,
                        endedAtMs = step.endedAtMs,
                        isBranch = step.isBranch,
                        interrupted = step.isInterrupted,
                        tappable = tappable(step.id),
                    )
            }
    }

private fun SessionChatStepStatus.toStepRailStatus(): OpenCodeStepRailStatus =
    when (this) {
        SessionChatStepStatus.Pending -> OpenCodeStepRailStatus.Pending
        SessionChatStepStatus.Running -> OpenCodeStepRailStatus.Running
        SessionChatStepStatus.Completed -> OpenCodeStepRailStatus.Completed
        SessionChatStepStatus.Failed -> OpenCodeStepRailStatus.Failed
    }

/**
 * What a tool card or step tap opens in the standard timeline: the child
 * session, the owning turn's Changes, or the detail sheet. [messageId] owns
 * the tool.
 */
private class ToolTapTargets(
    val messageId: String,
    val supportsSessionMessageDiff: Boolean,
    val onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit,
    val onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit,
    val onToolDetailTap: (SessionChatToolDetailTarget) -> Unit,
) {
    fun toolCardAction(block: SessionChatTimelineBlock.ToolCard): (() -> Unit)? =
        when {
            block.childSessionId != null -> {
                {
                    onChildSessionTap(block.childSessionId, block.title)
                }
            }
            block.opensChangesReview && supportsSessionMessageDiff -> {
                {
                    onChangesReviewTap(messageId, block.changesInitialFilePath)
                }
            }
            else -> sessionChatToolCardDetail(block)?.let { target -> { onToolDetailTap(target) } }
        }

    // A rail or activity card step opens what the standard timeline opens for its tool card.
    fun stepAction(step: SessionChatStep): (() -> Unit)? {
        val card = step.card
        return when {
            card != null -> toolCardAction(card)
            else -> sessionChatStepDetail(step, supportsSessionMessageDiff)?.let { target -> { onToolDetailTap(target) } }
        }
    }
}

private fun thoughtDetailTarget(
    text: String,
    durationLabel: String?,
): SessionChatToolDetailTarget {
    val presentation = ToolResultInspector.thought(text, durationLabel)
    return SessionChatToolDetailTarget(
        title = presentation?.title ?: "Thought",
        body = text,
        presentation = presentation,
    )
}

private fun stepThoughtDuration(step: SessionChatStep): String? {
    val start = step.startedAtMs
    val end = step.endedAtMs
    if (start == null || end == null || end < start) return null
    return SessionChatTimelineBlocks.thoughtDurationLabel(end - start)
}

private fun sessionChatToolCardDetail(block: SessionChatTimelineBlock.ToolCard): SessionChatToolDetailTarget? {
    val presentation = block.presentation?.takeUnless { block.detailIsMarkdown }
    val text = block.detailText
    if (presentation == null && text == null) return null
    return SessionChatToolDetailTarget(
        title = presentation?.title ?: block.title,
        body = text ?: presentation?.raw.orEmpty(),
        bodyIsMarkdown = block.detailIsMarkdown,
        presentation = presentation,
    )
}

/**
 * The detail a step's tap shows (a thought, or tool output, failure or answer
 * text), or null when the tap navigates instead (a child session, Changes) or
 * there is nothing to show. The activity sheet shows it in place (#779).
 */
internal fun sessionChatStepDetail(
    step: SessionChatStep,
    supportsSessionMessageDiff: Boolean,
): SessionChatToolDetailTarget? {
    val card = step.card
    val thoughtText = step.thoughtText
    return when {
        card != null ->
            if (card.childSessionId != null || (card.opensChangesReview && supportsSessionMessageDiff)) {
                null
            } else {
                sessionChatToolCardDetail(card)
            }
        thoughtText != null -> thoughtDetailTarget(thoughtText, stepThoughtDuration(step))
        else -> null
    }
}

/**
 * Activity card timeline (#779): maps a card onto [OpenCodeActivityCard].
 * Agent rows (running children live, every child settled) open their child
 * session through [stepAction]; the footer band, and the settled line, open
 * the activity sheet ([onOpen]).
 */
@Composable
private fun ActivityCardBlock(
    block: SessionChatTimelineBlock.ActivityCard,
    children: Map<String, SessionChatChildActivity>,
    footer: SessionChatActivityFooter?,
    onOpen: () -> Unit,
    stepAction: (SessionChatStep) -> (() -> Unit)?,
) {
    val actions = block.steps.filter { it.kind == SessionChatStepKind.ChildSession }.associate { it.id to stepAction(it) }
    if (block.isLive) {
        val live = remember(block.steps, children) { SessionChatActivityCard.live(block, children) }
        OpenCodeActivityCard(
            state = OpenCodeActivityCardState.Live(elapsedStartMs = live.startedAtMs),
            summary = live.summary,
            agents =
                live.agents.map {
                    OpenCodeActivityAgentRow(id = it.id, agentName = it.agentName, action = it.action, startedAtMs = it.startedAtMs)
                },
            // The Screen Model's footer hold decides what the band shows; the raw value is a fallback.
            current =
                when (footer) {
                    is SessionChatActivityFooter.Tool ->
                        OpenCodeActivityCurrentRow(
                            icon = footer.family.toActivityIcon(),
                            title = footer.title,
                            extraRunning = footer.extraRunning,
                            startedAtMs = footer.startedAtMs,
                            running = footer.running,
                            stepId = footer.stepId,
                        )
                    is SessionChatActivityFooter.Idle -> null
                    null ->
                        live.current?.let {
                            OpenCodeActivityCurrentRow(
                                icon = it.family.toActivityIcon(),
                                title = it.title,
                                extraRunning = it.extraRunning,
                                startedAtMs = it.startedAtMs,
                                stepId = it.stepId,
                            )
                        }
                },
            idleText =
                when (footer) {
                    is SessionChatActivityFooter.Idle -> footer.text
                    is SessionChatActivityFooter.Tool -> null
                    null -> live.idleText
                },
            onAgentTap = { id -> actions[id]?.invoke() },
            onOpen = onOpen,
        )
    } else {
        val settled = remember(block.steps) { SessionChatActivityCard.settled(block) }
        OpenCodeActivityCard(
            state =
                OpenCodeActivityCardState.Settled(
                    worstStatus =
                        if (settled.worstStatus == SessionChatStepStatus.Failed) {
                            OpenCodeActivityStatus.Failed
                        } else {
                            OpenCodeActivityStatus.Completed
                        },
                    stepCount = settled.stepCount,
                ),
            summary = settled.names.joinToString(", "),
            agents =
                settled.agents.map {
                    OpenCodeActivityAgentRow(id = it.id, agentName = it.agentName, action = it.action, startedAtMs = null)
                },
            current = null,
            idleText = null,
            onAgentTap = { id -> actions[id]?.invoke() },
            onOpen = onOpen,
        )
    }
}

/**
 * The live activity sheet (#779) for the card the screen model presents. Its
 * content is resolved from the current timeline every frame, so it grows in
 * place while open and closes once the card is gone. A step with a detail
 * shows it in the same sheet, with a back arrow to the list; a step that
 * navigates (child session, Changes) closes the sheet first, then opens what
 * the standard timeline opens.
 */
@Composable
private fun ActivityCardSheet(props: SessionChatTimelineViewProps) {
    val blockId = props.presentedActivityCard
    val resolved =
        remember(props.messages, blockId, props.activityCardChildren) {
            blockId?.let { SessionChatActivityCard.sheet(props.messages, it, props.activityCardChildren) }
        }
    // The exit motion keeps showing the last content instead of an empty `Used 0` sheet.
    val lastContent = remember { mutableStateOf<SessionChatActivitySheetContent?>(null) }
    if (resolved != null) lastContent.value = resolved
    val visible = resolved != null
    val content = resolved ?: lastContent.value
    val taps =
        content?.let {
            ToolTapTargets(
                messageId = it.messageId,
                supportsSessionMessageDiff = props.supportsSessionMessageDiff,
                onChildSessionTap = props.onChildSessionTap,
                onChangesReviewTap = props.onChangesReviewTap,
                onToolDetailTap = props.onToolDetailTap,
            )
        }
    val stepsById = content?.let { sheet -> (sheet.steps + sheet.agents.map { it.step }).associateBy { it.id } }.orEmpty()

    fun detailOf(step: SessionChatStep): SessionChatToolDetailTarget? =
        if (step.kind == SessionChatStepKind.ChildSession) null else sessionChatStepDetail(step, props.supportsSessionMessageDiff)

    fun tap(id: String) {
        val step = stepsById[id] ?: return
        if (detailOf(step) != null) {
            props.onOpenActivityCardStep(id)
            return
        }
        val action = taps?.stepAction(step) ?: return
        props.onDismissActivityCard()
        action()
    }
    // Resolved live too: a running tool's output grows while its detail shows.
    val detailStepId = props.activityCardDetailStepId
    val detailTarget = detailStepId?.let { id -> stepsById[id]?.let(::detailOf) }
    val detail = detailTarget?.let { target -> detailStepId?.let { OpenCodeActivitySheetDetail(stepId = it, title = target.title) } }
    OpenCodeActivitySheet(
        visible = visible,
        live = content?.isLive == true,
        stepCount = content?.stepCount ?: 0,
        startedAtMs = content?.startedAtMs,
        agents =
            content?.agents.orEmpty().map { agent ->
                OpenCodeActivitySheetAgent(
                    id = agent.step.id,
                    agentName = agent.agentName,
                    description = agent.description,
                    caption = agent.caption,
                    status = agent.step.toActivityStatus(),
                    startedAtMs = agent.step.startedAtMs,
                    recent =
                        agent.recent.map { sub ->
                            OpenCodeActivitySheetSubStep(id = sub.id, title = sub.title, status = sub.toActivityStatus())
                        },
                    tappable = taps?.stepAction(agent.step) != null,
                )
            },
        steps =
            content?.steps.orEmpty().map { step ->
                OpenCodeActivitySheetStep(
                    id = step.id,
                    icon = step.family.toActivityIcon(),
                    title = step.title,
                    detail = SessionChatActivityCard.sheetDetail(step),
                    status = step.toActivityStatus(),
                    additions = step.additions,
                    deletions = step.deletions,
                    startedAtMs = step.startedAtMs,
                    tappable = taps?.stepAction(step) != null,
                )
            },
        onAgentTap = ::tap,
        onStepTap = ::tap,
        onDismissRequest = props.onDismissActivityCard,
        detail = detail,
        onDetailBack = props.onCloseActivityCardStep,
        detailContent = { detailTarget?.let { SessionChatToolDetailBody(it) } },
    )
}

private fun SessionChatStepFamily?.toActivityIcon(): OpenCodeActivityIcon =
    when (this) {
        SessionChatStepFamily.Read -> OpenCodeActivityIcon.Read
        SessionChatStepFamily.Search -> OpenCodeActivityIcon.Search
        SessionChatStepFamily.Fetch -> OpenCodeActivityIcon.Fetch
        SessionChatStepFamily.Edit -> OpenCodeActivityIcon.Edit
        SessionChatStepFamily.Skill -> OpenCodeActivityIcon.Skill
        SessionChatStepFamily.Thought -> OpenCodeActivityIcon.Thought
        SessionChatStepFamily.Agent -> OpenCodeActivityIcon.Agent
        SessionChatStepFamily.Command, null -> OpenCodeActivityIcon.Command
    }

private fun SessionChatStep.toActivityStatus(): OpenCodeActivityStatus =
    when (status) {
        SessionChatStepStatus.Pending -> OpenCodeActivityStatus.Pending
        SessionChatStepStatus.Running -> OpenCodeActivityStatus.Running
        SessionChatStepStatus.Completed -> OpenCodeActivityStatus.Completed
        SessionChatStepStatus.Failed -> if (isInterrupted) OpenCodeActivityStatus.Interrupted else OpenCodeActivityStatus.Failed
    }

/** A v2 timeline notice row; tapping a row with a value expands it in place. */
@Composable
private fun TimelineNoticeBlock(block: SessionChatTimelineBlock.Notice) {
    val line = SessionChatNoticeCopy.line(block.notice, LocalNoticeModelNames.current)
    var expanded by rememberSaveable(block.blockID) { mutableStateOf(false) }
    OpenCodeTimelineNotice(
        label = line.label,
        value = line.value,
        variant = if (line.isMove) OpenCodeTimelineNoticeVariant.Move else OpenCodeTimelineNoticeVariant.Change,
        state = if (expanded) OpenCodeTimelineNoticeState.Expanded else OpenCodeTimelineNoticeState.Collapsed,
        onToggle = { expanded = !expanded },
    )
}

@Composable
private fun FailureLineBlock(
    block: SessionChatTimelineBlock.FailureLine,
    onToolDetailTap: (SessionChatToolDetailTarget) -> Unit,
) {
    val target =
        sessionFailureDetailTarget(
            text = block.text,
            providerBody = block.providerBody,
            action = block.action,
            actionLabel = block.actionLabel,
        )
    SessionFailureCaption(
        text = block.text,
        isNeutral = block.isNeutral,
        onOpen = target?.let { detail -> { onToolDetailTap(detail) } },
        retryAtEpochMs = block.retryAtEpochMs.takeIf { block.blockID == LocalRetryCountdownBlockId.current },
    )
}

private const val STEP_PARAGRAPH_PLACEHOLDER = "Worked without commentary"
private val StepParagraphTextPaddingHorizontal = 4.dp

/**
 * Chip timeline paragraph: narration (or the muted placeholder), a wrapping
 * chip row led by the explore summary and collapsed by
 * [StepParagraphChips.visibility] with one trailing `+N more`, then one error
 * caption per failed chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepParagraphBlock(
    block: SessionChatTimelineBlock.StepParagraph,
    renderedMarkdown: MarkdownDocument?,
    isStreaming: Boolean,
    onOpenMessageActions: () -> Unit,
    chipAction: (SessionChatTimelineBlock.ToolChip) -> (() -> Unit)?,
    exploreAction: (SessionChatTimelineBlock.ExploreSummary) -> Unit,
    expandedThrough: Int?,
    onExpand: () -> Unit,
) {
    val (visible, hiddenCount) = StepParagraphChips.visibility(block.chips, expandedThrough)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (block.text != null) {
            AssistantMessageBubble(
                text = block.text,
                renderedMarkdown = renderedMarkdown,
                isStreaming = isStreaming,
                onTextTap = onOpenMessageActions,
                presentation = OpenCodeAssistantMessagePresentation.Plain,
            )
        } else {
            Text(
                text = STEP_PARAGRAPH_PLACEHOLDER,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = openCodeColor("text-weak"),
                modifier = Modifier.padding(horizontal = StepParagraphTextPaddingHorizontal),
            )
        }
        if (block.chips.isNotEmpty() || block.exploreSummary != null) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = StepParagraphTextPaddingHorizontal),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                block.exploreSummary?.let { summary ->
                    key("explore") {
                        OpenCodeToolChip(
                            variant = OpenCodeToolChipVariant.Explore(summary.label),
                            status = summary.status.toToolChipStatus(),
                            onClick = { exploreAction(summary) },
                        )
                    }
                }
                visible.forEach { chip ->
                    key(chip.id) {
                        val elapsed = rememberElapsedLabel(chip.runningSinceEpochMs)
                        OpenCodeToolChip(
                            variant = chip.toToolChipVariant(),
                            status = chip.status.toToolChipStatus(interrupted = chip.card.isInterrupted),
                            trailingText =
                                listOfNotNull(chip.displayTrailingText, elapsed)
                                    .takeIf { it.isNotEmpty() }
                                    ?.joinToString(" \u00b7 "),
                            onClick = chipAction(chip),
                        )
                    }
                }
                if (hiddenCount > 0) {
                    OpenCodeToolChip(
                        variant = OpenCodeToolChipVariant.More(hiddenCount),
                        onClick = onExpand,
                    )
                }
            }
        }
        block.errorLines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = openCodeColor("error"),
                modifier = Modifier.padding(horizontal = StepParagraphTextPaddingHorizontal),
            )
        }
    }
}

private fun SessionChatTimelineBlock.ToolChip.toToolChipVariant(): OpenCodeToolChipVariant =
    when (kind) {
        ToolChipKind.File -> OpenCodeToolChipVariant.File(label = label, additions = additions, deletions = deletions)
        ToolChipKind.Command -> OpenCodeToolChipVariant.Command(label = label)
        ToolChipKind.Agent -> OpenCodeToolChipVariant.Agent(name = label, brief = brief)
        ToolChipKind.Skill -> OpenCodeToolChipVariant.Skill(name = label)
    }

private fun ToolStatus.toToolChipStatus(interrupted: Boolean = false): OpenCodeToolChipStatus =
    when {
        this == ToolStatus.Error && interrupted -> OpenCodeToolChipStatus.Interrupted
        else -> toToolChipStatusBase()
    }

private fun ToolStatus.toToolChipStatusBase(): OpenCodeToolChipStatus =
    when (this) {
        ToolStatus.Pending -> OpenCodeToolChipStatus.Pending
        ToolStatus.Running -> OpenCodeToolChipStatus.Running
        ToolStatus.Completed -> OpenCodeToolChipStatus.Completed
        ToolStatus.Error -> OpenCodeToolChipStatus.Error
    }

@Composable
private fun AssistantMessageBubble(
    text: String,
    renderedMarkdown: MarkdownDocument?,
    isStreaming: Boolean = false,
    onTextTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    presentation: OpenCodeAssistantMessagePresentation = OpenCodeAssistantMessagePresentation.Bubble,
) {
    val shouldDeferMarkdown =
        SessionChatMarkdownPaint.shouldDefer(
            textLength = text.length,
            hasMarkdown = renderedMarkdown != null,
        )
    // If markdown is already prepared when the bubble first appears (hold-ready
    // open / reconfigure with doc), paint immediately. Defer only when a doc
    // arrives later on an already-visible plain bubble.
    var paintMarkdown by remember {
        mutableStateOf(renderedMarkdown != null)
    }
    LaunchedEffect(renderedMarkdown == null, shouldDeferMarkdown) {
        when {
            renderedMarkdown == null -> paintMarkdown = false
            !shouldDeferMarkdown -> paintMarkdown = true
            // Late ≥3k doc: keep current paintMarkdown (false until delay).
        }
    }
    LaunchedEffect(renderedMarkdown, text.length, paintMarkdown) {
        if (renderedMarkdown == null || paintMarkdown) {
            return@LaunchedEffect
        }
        if (!shouldDeferMarkdown) {
            paintMarkdown = true
            return@LaunchedEffect
        }
        yield()
        delay(SessionChatMarkdownPaint.DEFER_MILLISECONDS)
        paintMarkdown = true
    }

    OpenCodeAssistantMessage(
        text = text,
        renderedMarkdown = renderedMarkdown.takeIf { paintMarkdown },
        isStreaming = isStreaming,
        modifier = modifier,
        presentation = presentation,
        onTextTap = onTextTap,
    )
}

/**
 * Session Chat–only large-markdown paint deferral for **late** doc arrival
 * (enrich while a plain bubble is already visible). Initial appear with a
 * prepared document paints immediately — including hold-ready opens.
 */
internal object SessionChatMarkdownPaint {
    const val DEFERRED_CHARACTER_THRESHOLD = 3_000
    const val DEFER_MILLISECONDS = 32L

    fun shouldDefer(
        textLength: Int,
        hasMarkdown: Boolean,
    ): Boolean = hasMarkdown && textLength >= DEFERRED_CHARACTER_THRESHOLD
}

private fun ToolStatus.toToolCallCardStatus(interrupted: Boolean = false): OpenCodeToolCallCardStatus =
    when {
        this == ToolStatus.Error && interrupted -> OpenCodeToolCallCardStatus.Interrupted
        else -> toToolCallCardStatusBase()
    }

private fun ToolStatus.toToolCallCardStatusBase(): OpenCodeToolCallCardStatus =
    when (this) {
        ToolStatus.Pending -> OpenCodeToolCallCardStatus.Pending
        ToolStatus.Running -> OpenCodeToolCallCardStatus.Running
        ToolStatus.Completed -> OpenCodeToolCallCardStatus.Completed
        ToolStatus.Error -> OpenCodeToolCallCardStatus.Error
    }

private const val SESSION_CHAT_BOTTOM_ANCHOR_KEY = "session-chat-timeline-bottom"
private const val SESSION_CHAT_STATUS_INDICATOR_KEY = "session-chat-status-indicators"

@Composable
private fun FallbackMessageBubble(
    roleLabel: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "$roleLabel: ${text.ifBlank { "…" }}",
        modifier =
            modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                ).padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Syncing label the top pill slot shows, lagging [label] by
 * [SessionChatTopPill.SYNCING_SHOW_DELAY_MS] and held for
 * [SessionChatTopPill.SYNCING_MINIMUM_VISIBLE_MS] once shown.
 */
@Composable
private fun rememberDisplayedSyncingLabel(label: String?): String? {
    var displayed by remember { mutableStateOf<String?>(null) }
    var shownAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(label) {
        if (label != null) {
            if (displayed == null) {
                delay(SessionChatTopPill.SYNCING_SHOW_DELAY_MS)
                shownAtMs = SystemClock.uptimeMillis()
            }
            displayed = label
        } else if (displayed != null) {
            val shownForMs = SystemClock.uptimeMillis() - shownAtMs
            delay((SessionChatTopPill.SYNCING_MINIMUM_VISIBLE_MS - shownForMs).coerceAtLeast(0L))
            displayed = null
        }
    }
    return displayed
}
