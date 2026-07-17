package dev.opencode.mobile.ui.sessionchat.view

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.DragInteraction
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
import dev.opencode.mobile.ui.scroll.MessageListTimelineDefaults
import dev.opencode.mobile.ui.scroll.messageListAnimateLastMessageSize
import dev.opencode.mobile.ui.scroll.messageListTimelineItemSpacing
import dev.opencode.mobile.ui.scroll.rememberMessageListTimeline
import dev.opencode.mobile.ui.sessionchat.AttachmentPresentationMapper
import dev.opencode.mobile.ui.sessionchat.ReviewChangesPillVisibility
import dev.opencode.mobile.ui.sessionchat.ReviewChangesSummary
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.displayLabel

internal data class SessionChatTimelineViewProps(
    val timeline: SessionChatTimelineState,
    val messages: List<SessionChatMessageItem>,
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
    val timeline =
        rememberMessageListTimeline(
            messageCount = messages.size,
            firstMessageId = messages.firstOrNull()?.id,
            lastMessageId = messages.lastOrNull()?.id,
            layoutSignature = messages.layoutSignature(),
            autoFollowEnabled = true,
        )
    val focusManager = LocalFocusManager.current

    // Clear IME only on user drag — not sticky-follow / programmatic scroll-to-end.
    LaunchedEffect(timeline.listState) {
        timeline.listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                focusManager.clearFocus()
            }
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
                items = messages,
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
                    animateSizeChanges = isNewestMessage,
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
    val textBlocks = blocks.filterIsInstance<SessionChatTimelineBlock.Text>()
    return blocks.mapNotNull { block ->
        when (block) {
            is SessionChatTimelineBlock.Text ->
                OpenCodeOutgoingMessageContent.Text(
                    text = block.text,
                    renderedMarkdown = renderedMarkdown.takeIf { textBlocks.size == 1 },
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
            listOf(SessionChatTimelineBlock.Text(text = message.text))
        }
    val singleTextBlock = blocks.count { it is SessionChatTimelineBlock.Text } == 1
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        blocks.forEach { block ->
            when (block) {
                is SessionChatTimelineBlock.Text ->
                    AssistantMessageBubble(
                        text = block.text,
                        renderedMarkdown = message.renderedMarkdown.takeIf { singleTextBlock },
                        modifier =
                            messageActionsModifier(
                                renderedMarkdown = message.renderedMarkdown.takeIf { singleTextBlock },
                                onOpenMessageActions = onOpenMessageActions,
                                messageDescription = "Assistant message",
                            ),
                    )
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

@Composable
private fun AssistantMessageBubble(
    text: String,
    renderedMarkdown: MarkdownDocument?,
    modifier: Modifier = Modifier,
) {
    val bubbleModifier =
        modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)

    when {
        renderedMarkdown != null ->
            OpenCodeRenderedMarkdown(
                document = renderedMarkdown,
                variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
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

private fun ToolStatus.toToolCallCardStatus(): OpenCodeToolCallCardStatus =
    when (this) {
        ToolStatus.Pending -> OpenCodeToolCallCardStatus.Pending
        ToolStatus.Running -> OpenCodeToolCallCardStatus.Running
        ToolStatus.Completed -> OpenCodeToolCallCardStatus.Completed
        ToolStatus.Error -> OpenCodeToolCallCardStatus.Error
    }

private const val SESSION_CHAT_BOTTOM_ANCHOR_KEY = "session-chat-timeline-bottom"

private fun List<SessionChatMessageItem>.layoutSignature(): Int =
    fold(size) { result, message ->
        31 * result +
            message.id.hashCode() +
            message.text.length +
            message.blocks.size +
            message.attachments.size +
            message.renderedMarkdown.layoutSignature() +
            if (message.isStreaming) 1 else 0
    }

private fun MarkdownDocument?.layoutSignature(): Int =
    when (this) {
        null -> 0
        else -> 31 * sourceHash + blocks.size
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
