package dev.opencode.mobile.ui.sessionchat.view

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.components.OpenCodeMessageAttachment
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessageContent
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant
import dev.opencode.mobile.ui.components.OpenCodeReviewChangesPill
import dev.opencode.mobile.ui.components.OpenCodeReviewChangesPillVariant
import dev.opencode.mobile.ui.components.OpenCodeToolCallCard
import dev.opencode.mobile.ui.components.OpenCodeToolCallCardStatus
import dev.opencode.mobile.ui.scroll.MessageListReversedTimelineColumn
import dev.opencode.mobile.ui.scroll.MessageListScrollToEndOverlay
import dev.opencode.mobile.ui.scroll.MessageListTimelineApplyHold
import dev.opencode.mobile.ui.scroll.MessageListTimelineDefaults
import dev.opencode.mobile.ui.scroll.messageListAnimateLastMessageSize
import dev.opencode.mobile.ui.scroll.messageListTimelineItemSpacing
import dev.opencode.mobile.ui.scroll.rememberMessageListTimeline
import dev.opencode.mobile.ui.sessionchat.AttachmentPresentationMapper
import dev.opencode.mobile.ui.sessionchat.PresentationBlockId
import dev.opencode.mobile.ui.sessionchat.ReviewChangesPillVisibility
import dev.opencode.mobile.ui.sessionchat.ReviewChangesSummary
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.TimelineApply
import dev.opencode.mobile.ui.sessionchat.TimelineFrame
import dev.opencode.mobile.ui.sessionchat.displayLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

internal data class SessionChatTimelineViewProps(
    val timeline: SessionChatTimelineState,
    val messages: List<SessionChatMessageItem>,
    val timelineFrame: TimelineFrame? = null,
    val isRefreshing: Boolean,
    val errorMessage: String?,
    val reviewChangesSummary: ReviewChangesSummary?,
    val onRefresh: () -> Unit,
    val onReviewChangesClick: () -> Unit,
    val onMessageActionsTap: (SessionChatMessageActionsTarget) -> Unit,
    val onToolDetailTap: (SessionChatToolDetailTarget) -> Unit = {},
    val onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit = { _, _ -> },
    val onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionChatTimelineView(
    props: SessionChatTimelineViewProps,
    modifier: Modifier = Modifier,
) {
    when (props.timeline) {
        SessionChatTimelineState.Loading -> Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        SessionChatTimelineState.Error -> Box(
            modifier = modifier
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

        SessionChatTimelineState.Empty -> Box(
            modifier = modifier
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
                reviewChangesSummary = props.reviewChangesSummary,
                onRefresh = props.onRefresh,
                onReviewChangesClick = props.onReviewChangesClick,
                onMessageActionsTap = props.onMessageActionsTap,
                onToolDetailTap = props.onToolDetailTap,
                onChildSessionTap = props.onChildSessionTap,
                onChangesReviewTap = props.onChangesReviewTap,
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
    reviewChangesSummary: ReviewChangesSummary?,
    onRefresh: () -> Unit,
    onReviewChangesClick: () -> Unit,
    onMessageActionsTap: (SessionChatMessageActionsTarget) -> Unit,
    onToolDetailTap: (SessionChatToolDetailTarget) -> Unit,
    onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit,
    onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val isScrollInProgress by remember(listState) {
        derivedStateOf { listState.isScrollInProgress }
    }

    // Host-side latest-wins hold under drag/fling (iOS pendingApply parity).
    // Required frames flush immediately; other kinds keep the committed snapshot.
    var committedMessages by remember { mutableStateOf(messages) }
    var committedFrame by remember { mutableStateOf(timelineFrame) }
    LaunchedEffect(messages, timelineFrame, isScrollInProgress) {
        val hold =
            MessageListTimelineApplyHold.shouldHold(
                isScrollInProgress = isScrollInProgress,
                isRequired = timelineFrame?.isRequired == true,
            )
        if (!hold) {
            committedMessages = messages
            committedFrame = timelineFrame
        } else {
            runCatching {
                Log.d(
                    "SessionChatScroll",
                    "timelineApplyHold scrollInProgress=true required=false " +
                        "incomingCount=${messages.size} committedCount=${committedMessages.size}",
                )
            }
        }
    }

    val apply = committedFrame?.let(TimelineApply::classify)
    val layoutAffectingRevision = apply?.layoutAffectingRevision ?: 0L
    val growBottomRevision = apply?.growBottomRevision ?: 0L
    val messageIds = remember(committedMessages) { committedMessages.map { it.id } }
    val timeline =
        rememberMessageListTimeline(
            messageCount = committedMessages.size,
            firstMessageId = committedMessages.firstOrNull()?.id,
            lastMessageId = committedMessages.lastOrNull()?.id,
            layoutAffectingRevision = layoutAffectingRevision,
            growBottomRevision = growBottomRevision,
            autoFollowEnabled = true,
            messageIdsChronological = messageIds,
            listState = listState,
        )

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
                modifier = Modifier.fillMaxSize(),
                key = { message -> message.id },
            ) { message, isNewestMessage, onContentSizeSettled ->
                SessionChatMessageRow(
                    message = message,
                    onMessageActionsTap = onMessageActionsTap,
                    onToolDetailTap = onToolDetailTap,
                    onChildSessionTap = onChildSessionTap,
                    onChangesReviewTap = onChangesReviewTap,
                    // Streaming height growth is GrowBottom + unanimated layout; do not
                    // animateContentSize on production Session Chat (gallery may still demo it).
                    animateSizeChanges = false,
                    onContentSizeAnimationFinished = onContentSizeSettled,
                    modifier = Modifier.messageListTimelineItemSpacing(isNewestMessage),
                )
            }
        }

        MessageListScrollToEndOverlay(
            visible = timeline.showScrollToEnd,
            onClick = {
                timeline.scrollController.animateToEnd(
                    messageCount = timeline.messageCount,
                    tolerancePx = timeline.autoFollowTolerancePx,
                    bottomAnchorHeightPx = timeline.bottomAnchorHeightPx,
                    autoFollowEnabled = true,
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        val showReviewChanges =
            ReviewChangesPillVisibility.shouldShow(
                summary = reviewChangesSummary,
                isPinnedToEnd = timeline.isPinnedToEnd,
                showScrollToEnd = timeline.showScrollToEnd,
            )
        AnimatedVisibility(
            visible = showReviewChanges,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = MessageListTimelineDefaults.scrollToEndPillBottomPadding),
        ) {
            reviewChangesSummary?.let { summary ->
                OpenCodeReviewChangesPill(
                    variant = OpenCodeReviewChangesPillVariant.LatestTurn,
                    changedFileCount = summary.fileCount,
                    additions = summary.additions,
                    deletions = summary.deletions,
                    onClick = onReviewChangesClick,
                )
            }
        }
    }
}

@Composable
private fun SessionChatMessageRow(
    message: SessionChatMessageItem,
    onMessageActionsTap: (SessionChatMessageActionsTarget) -> Unit,
    onToolDetailTap: (SessionChatToolDetailTarget) -> Unit,
    onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit,
    onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit,
    animateSizeChanges: Boolean,
    onContentSizeAnimationFinished: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    fun openMessageActions(showsRevertMessage: Boolean) {
        onMessageActionsTap(
            SessionChatMessageActionsTarget(
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
                    onLongClick =
                        if (message.renderedMarkdown != null) {
                            { openMessageActions(showsRevertMessage = true) }
                        } else {
                            null
                        },
                )

            MessageRole.Assistant ->
                AssistantMessageContent(
                    message = message,
                    onOpenMessageActions = { openMessageActions(showsRevertMessage = false) },
                    onToolDetailTap = onToolDetailTap,
                    onChildSessionTap = onChildSessionTap,
                    onChangesReviewTap = onChangesReviewTap,
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
            is SessionChatTimelineBlock.ToolCard, is SessionChatTimelineBlock.ToolGroup -> null
        }
    }
}

private fun messageActionsModifier(
    renderedMarkdown: MarkdownDocument?,
    onOpenMessageActions: () -> Unit,
    messageDescription: String,
): Modifier =
    if (renderedMarkdown != null) {
        Modifier.combinedClickable(
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
    onChildSessionTap: (sessionId: String, sessionTitle: String) -> Unit,
    onChangesReviewTap: (assistantMessageId: String, initialFilePath: String?) -> Unit,
) {
    val blocks =
        message.blocks.ifEmpty {
            listOf(
                SessionChatTimelineBlock.Text(
                    blockID = PresentationBlockId.messageFallback(message.id),
                    text = message.text,
                ),
            )
        }
    val textBlockCount = blocks.count { it is SessionChatTimelineBlock.Text }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        blocks.forEach { block ->
            key(block.blockID) {
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
                            onClick =
                                when {
                                    block.childSessionId != null -> {
                                        {
                                            onChildSessionTap(block.childSessionId, block.title)
                                        }
                                    }
                                    block.opensChangesReview -> {
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
                                },
                        )
                    is SessionChatTimelineBlock.ToolGroup ->
                        OpenCodeToolCallCard(
                            status = block.status.toToolCallCardStatus(),
                            title = block.title,
                            subtitle = block.subtitle,
                            errorText = block.errorText,
                        )
                }
            }
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    text: String,
    renderedMarkdown: MarkdownDocument?,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bubbleModifier =
        modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)

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

    when {
        paintMarkdown && renderedMarkdown != null ->
            OpenCodeRenderedMarkdown(
                document = renderedMarkdown,
                variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
                isStreaming = isStreaming,
                modifier = bubbleModifier,
            )

        else ->
            Text(
                text = text.ifBlank { "…" },
                modifier = bubbleModifier,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
    }
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
