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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeActionPill
import dev.opencode.mobile.ui.components.OpenCodeActionPillVariant
import dev.opencode.mobile.ui.components.OpenCodeAssistantMessage
import dev.opencode.mobile.ui.components.OpenCodeAssistantMessagePresentation
import dev.opencode.mobile.ui.components.OpenCodeMessageAttachment
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessageContent
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchPill
import dev.opencode.mobile.ui.components.OpenCodeSessionTodosPill
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeSyncingPill
import dev.opencode.mobile.ui.components.OpenCodeToolCallCard
import dev.opencode.mobile.ui.components.OpenCodeToolCallCardStatus
import dev.opencode.mobile.ui.components.OpenCodeToolChip
import dev.opencode.mobile.ui.components.OpenCodeToolChipStatus
import dev.opencode.mobile.ui.components.OpenCodeToolChipVariant
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
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
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
    /** Extra bottom inset so the timeline can scroll under a floating composer. */
    val bottomOverlayInset: Dp = 0.dp,
    val onRefresh: () -> Unit,
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
    /**
     * Chip-timeline paragraphs expanded past `+N more` (expansion id to chip
     * count at tap time); owned by the Session Chat screen model.
     */
    val expandedStepParagraphs: Map<String, Int> = emptyMap(),
    val onExpandStepParagraph: (expansionId: String, chipCount: Int) -> Unit = { _, _ -> },
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
            Box(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = props.errorMessage ?: "Unable to load messages",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

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
                expandedStepParagraphs = props.expandedStepParagraphs,
                onExpandStepParagraph = props.onExpandStepParagraph,
                modifier = modifier,
            )
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
    expandedStepParagraphs: Map<String, Int>,
    onExpandStepParagraph: (expansionId: String, chipCount: Int) -> Unit,
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
    val messageIds = remember(committedMessages) { committedMessages.map { it.id } }
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
    val oldestVisible by remember(listState, oldestMessageId) {
        derivedStateOf {
            oldestMessageId != null &&
                listState.layoutInfo.visibleItemsInfo.any { it.key == oldestMessageId }
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
                key = { message -> message.id },
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
                            SessionChatWorkingIndicatorRow(
                                label = label,
                                fading = committedWorkingFading,
                                collapsed = committedWorkingCollapsed,
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
                    expandedStepParagraphs = expandedStepParagraphs,
                    onExpandStepParagraph = onExpandStepParagraph,
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
    expandedStepParagraphs: Map<String, Int>,
    onExpandStepParagraph: (expansionId: String, chipCount: Int) -> Unit,
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
                    onClick =
                        if (message.renderedMarkdown == null) {
                            { openMessageActions(showsRevertMessage = true) }
                        } else {
                            null
                        },
                    // Always long-pressable so plain and markdown bubbles match;
                    // markdown still uses tap only for links (parent onClick stays null).
                    onLongClick = { openMessageActions(showsRevertMessage = true) },
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
                )

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
            -> null
        }
    }
}

private fun messageActionsModifier(
    renderedMarkdown: MarkdownDocument?,
    onOpenMessageActions: () -> Unit,
    messageDescription: String,
): Modifier =
    if (renderedMarkdown != null) {
        Modifier
            .combinedClickable(
                onClick = {},
                onLongClick = onOpenMessageActions,
                onLongClickLabel = "Show message actions",
                role = Role.Button,
            ).semantics {
                contentDescription = "$messageDescription. Long press to show message actions"
            }
    } else {
        Modifier.clickable(onClick = onOpenMessageActions).semantics {
            role = Role.Button
            contentDescription = "$messageDescription. Shows message actions"
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
) {
    // Rows never render empty — unpaintable blocks are filtered and an empty
    // result falls back to the live placeholder.
    val blocks = SessionChatTimelineBlocks.paintedBlocks(message)
    val streamingStepParagraphBlockId =
        SessionChatTimelineBlocks.streamingStepParagraphBlockId(blocks, message.isStreaming)
    // A sole narration carrier (text block or step paragraph with text) takes
    // the message-scoped markdown, as before the chip timeline.
    val textBlockCount = blocks.count { it.markdownText != null }

    fun toolCardAction(block: SessionChatTimelineBlock.ToolCard): (() -> Unit)? =
        when {
            block.childSessionId != null -> {
                {
                    onChildSessionTap(block.childSessionId, block.title)
                }
            }
            block.opensChangesReview && supportsSessionMessageDiff -> {
                {
                    onChangesReviewTap(message.id, block.changesInitialFilePath)
                }
            }
            block.detailText != null -> {
                {
                    onToolDetailTap(
                        SessionChatToolDetailTarget(
                            title = block.title,
                            body = block.detailText,
                        ),
                    )
                }
            }
            else -> null
        }
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
                            modifier =
                                messageActionsModifier(
                                    renderedMarkdown = renderedMarkdown,
                                    onOpenMessageActions = onOpenMessageActions,
                                    messageDescription = "Assistant message",
                                ),
                        )
                    }
                    is SessionChatTimelineBlock.Attachment ->
                        OpenCodeMessageAttachment(AttachmentPresentationMapper.from(block.attachment))
                    is SessionChatTimelineBlock.ToolCard ->
                        OpenCodeToolCallCard(
                            status = block.status.toToolCallCardStatus(),
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
                        ReasoningBlock(
                            text = block.text,
                            isStreaming = false,
                            durationMs = block.durationMs,
                            onClick =
                                block.text.takeIf { it.isNotBlank() }?.let { body ->
                                    {
                                        onToolDetailTap(
                                            SessionChatToolDetailTarget(
                                                title = "Thought",
                                                body = body,
                                            ),
                                        )
                                    }
                                },
                        )
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
                }
            }
        }
    }
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
                modifier =
                    messageActionsModifier(
                        renderedMarkdown = renderedMarkdown,
                        onOpenMessageActions = onOpenMessageActions,
                        messageDescription = "Assistant message",
                    ),
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
                        OpenCodeToolChip(
                            variant = chip.toToolChipVariant(),
                            status = chip.status.toToolChipStatus(),
                            trailingText = chip.displayTrailingText,
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

private fun ToolStatus.toToolChipStatus(): OpenCodeToolChipStatus =
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

private fun ToolStatus.toToolCallCardStatus(): OpenCodeToolCallCardStatus =
    when (this) {
        ToolStatus.Pending -> OpenCodeToolCallCardStatus.Pending
        ToolStatus.Running -> OpenCodeToolCallCardStatus.Running
        ToolStatus.Completed -> OpenCodeToolCallCardStatus.Completed
        ToolStatus.Error -> OpenCodeToolCallCardStatus.Error
    }

private const val SESSION_CHAT_BOTTOM_ANCHOR_KEY = "session-chat-timeline-bottom"
private const val SESSION_CHAT_STATUS_INDICATOR_KEY = "session-chat-status-indicators"

/** Feature-local Working/Retrying/Compacting status chrome (not prose). */
@Composable
private fun SessionChatWorkingIndicatorRow(
    label: String,
    fading: Boolean = false,
    collapsed: Boolean = false,
) {
    val animateOut = collapsed && label == WorkingTurnIndicator.IDLE_LABEL
    val collapseSpec = if (animateOut) tween<Float>(durationMillis = 220) else snap<Float>()
    val contentAlpha by animateFloatAsState(
        targetValue =
            when {
                collapsed -> 0f
                fading -> 0.35f
                else -> 1f
            },
        animationSpec = collapseSpec,
        label = "session-chat-working-fade",
    )
    val heightFactor by animateFloatAsState(
        targetValue = if (collapsed) 0f else 1f,
        animationSpec = collapseSpec,
        label = "session-chat-working-collapse",
    )
    Layout(
        modifier =
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .semantics {
                    if (collapsed) {
                        hideFromAccessibility()
                    } else {
                        contentDescription = label
                    }
                },
        content = {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 12.dp, bottom = 8.dp)
                        .alpha(contentAlpha),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionChatWorkingStatusMark(isIdle = WorkingTurnIndicator.showsIdleMark(label))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        val height = (placeable.height * heightFactor).toInt()
        layout(placeable.width, height) {
            placeable.placeRelative(0, 0)
        }
    }
}

@Composable
private fun SessionChatWorkingStatusMark(isIdle: Boolean) {
    val progress by animateFloatAsState(
        targetValue = if (isIdle) 1f else 0f,
        animationSpec = if (isIdle) tween(durationMillis = 220) else snap(),
        label = "session-chat-working-idle-mark",
    )
    Box(
        modifier = Modifier.size(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        SessionChatWorkingStatusDot(
            modifier = Modifier.alpha(1f - progress),
        )
        Icon(
            imageVector = OpenCodeIcons.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        alpha = progress
                        val scale = 0.4f + (0.6f * progress)
                        scaleX = scale
                        scaleY = scale
                    },
        )
    }
}

@Composable
private fun SessionChatWorkingStatusDot(modifier: Modifier = Modifier) {
    val pulse =
        rememberInfiniteTransition(label = "session-chat-working-dot").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 900),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "session-chat-working-dot-alpha",
        )
    Box(
        modifier =
            modifier
                .size(6.dp)
                .alpha(pulse.value)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                ),
    )
}

@Composable
private fun ReasoningBlock(
    text: String,
    isStreaming: Boolean,
    durationMs: Long?,
    onClick: (() -> Unit)?,
) {
    val duration = SessionChatTimelineBlocks.thoughtDurationLabel(durationMs)
    val label =
        when {
            isStreaming -> "Thinking"
            duration != null -> "Thought · $duration"
            else -> "Thought"
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 12.dp, bottom = 8.dp)
                .then(
                    if (onClick != null) {
                        Modifier
                            .clickable(role = Role.Button, onClick = onClick)
                            .semantics { contentDescription = label }
                    } else {
                        Modifier.semantics { contentDescription = label }
                    },
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isStreaming) {
            SessionChatWorkingStatusDot()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
