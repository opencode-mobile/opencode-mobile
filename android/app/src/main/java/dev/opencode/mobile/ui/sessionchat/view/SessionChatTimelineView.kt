package dev.opencode.mobile.ui.sessionchat.view

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.components.OpenCodeActionPill
import dev.opencode.mobile.ui.components.OpenCodeActionPillVariant
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant
import dev.opencode.mobile.ui.scroll.MessageListAutoFollowState
import dev.opencode.mobile.ui.scroll.animateScrollToTimelineEnd
import dev.opencode.mobile.ui.scroll.animateScrollToTimelineEndAfterAppend
import dev.opencode.mobile.ui.scroll.isPinnedToTimelineEnd
import dev.opencode.mobile.ui.scroll.scrollToTimelineEndAfterLayoutChange
import dev.opencode.mobile.ui.scroll.shouldShowScrollToEndPill
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.displayLabel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal data class SessionChatTimelineViewProps(
    val timeline: SessionChatTimelineState,
    val messages: List<SessionChatMessageItem>,
    val isRefreshing: Boolean,
    val errorMessage: String?,
    val onRefresh: () -> Unit,
    val onRestoreComposerText: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionChatTimelineView(
    props: SessionChatTimelineViewProps,
    modifier: Modifier = Modifier,
) {
    when (props.timeline) {
        SessionChatTimelineState.Loading ->
            Box(
                modifier = modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        SessionChatTimelineState.Error ->
            Box(
                modifier = modifier.fillMaxWidth().padding(16.dp),
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
                modifier = modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No messages yet. Send a prompt to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        SessionChatTimelineState.Ready -> {
            val listState = rememberLazyListState()
            val revealThresholdMinPx = with(LocalDensity.current) { 120.dp.roundToPx() }
            val autoFollowTolerancePx = with(LocalDensity.current) { SessionChatAutoFollowTolerance.roundToPx() }
            val bottomAnchorHeightPx = with(LocalDensity.current) { SessionChatTimelineBottomAnchorHeight.roundToPx() }
            val isPinnedToEnd by remember(props.messages.size, autoFollowTolerancePx, bottomAnchorHeightPx) {
                derivedStateOf {
                    listState.isPinnedToTimelineEnd(
                        messageCount = props.messages.size,
                        tolerancePx = autoFollowTolerancePx,
                        bottomAnchorHeightPx = bottomAnchorHeightPx,
                    )
                }
            }
            val showScrollToEnd by remember(props.messages, revealThresholdMinPx) {
                derivedStateOf {
                    listState.shouldShowScrollToEndPill(
                        messageCount = props.messages.size,
                        revealThresholdMinPx = revealThresholdMinPx,
                    )
                }
            }
            val messageLayoutSignature = remember(props.messages) { props.messages.layoutSignature() }
            val coroutineScope = rememberCoroutineScope()
            val autoFollowState = remember { MessageListAutoFollowState(initialMessageCount = props.messages.size) }
            var initialTimelineEndScrollDone by remember { mutableStateOf(false) }

            suspend fun animateToEndAndArmFollow() {
                logScrollD("animate-to-end start count=${props.messages.size}")
                listState.animateScrollToTimelineEnd(messageCount = props.messages.size)
                withFrameNanos { }
                val settledPinned =
                    listState.isPinnedToTimelineEnd(
                        messageCount = props.messages.size,
                        tolerancePx = autoFollowTolerancePx,
                        bottomAnchorHeightPx = bottomAnchorHeightPx,
                    )
                autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd = settledPinned)
                logScrollD(
                    "animate-to-end settled count=${props.messages.size} pinned=$settledPinned " +
                        "first=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}",
                )
            }

            suspend fun animateAppendToEndAndArmFollow() {
                logScrollD("append-follow start count=${props.messages.size}")
                withFrameNanos { }
                listState.animateScrollToTimelineEndAfterAppend(messageCount = props.messages.size)
                withFrameNanos { }
                val settledPinned =
                    listState.isPinnedToTimelineEnd(
                        messageCount = props.messages.size,
                        tolerancePx = autoFollowTolerancePx,
                        bottomAnchorHeightPx = bottomAnchorHeightPx,
                    )
                autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd = settledPinned)
                logScrollD(
                    "append-follow settled count=${props.messages.size} pinned=$settledPinned " +
                        "first=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}",
                )
            }

            suspend fun snapLayoutChangeToEndAndArmFollow() {
                logScrollD("layout-snap start count=${props.messages.size}")
                listState.scrollToTimelineEndAfterLayoutChange(messageCount = props.messages.size)
                withFrameNanos { }
                val settledPinned =
                    listState.isPinnedToTimelineEnd(
                        messageCount = props.messages.size,
                        tolerancePx = autoFollowTolerancePx,
                        bottomAnchorHeightPx = bottomAnchorHeightPx,
                    )
                autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd = settledPinned)
                logScrollD(
                    "layout-snap settled count=${props.messages.size} pinned=$settledPinned " +
                        "first=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}",
                )
            }

            suspend fun followLayoutChangeAfterSettled() {
                logScrollD("layout-check scheduled count=${props.messages.size}")
                withFrameNanos { }
                withFrameNanos { }

                val pinned =
                    listState.isPinnedToTimelineEnd(
                        messageCount = props.messages.size,
                        tolerancePx = autoFollowTolerancePx,
                        bottomAnchorHeightPx = bottomAnchorHeightPx,
                    )

                when {
                    listState.isScrollInProgress || pinned -> {
                        logScrollD(
                            "layout-check no-op count=${props.messages.size} pinned=$pinned " +
                                "scrolling=${listState.isScrollInProgress}",
                        )
                        autoFollowState.updatePinnedState(
                            isPinnedToEnd = pinned,
                            messageCount = props.messages.size,
                        )
                    }
                    autoFollowState.shouldFollowLayoutChange(
                        autoFollowEnabled = true,
                        isPinnedToEnd = false,
                    ) -> {
                        logScrollD("layout-check follow count=${props.messages.size}")
                        snapLayoutChangeToEndAndArmFollow()
                    }
                    else -> {
                        logScrollD("layout-check disarm count=${props.messages.size}")
                        autoFollowState.updatePinnedState(
                            isPinnedToEnd = false,
                            messageCount = props.messages.size,
                        )
                    }
                }
            }

            LaunchedEffect(listState, props.messages.size, autoFollowTolerancePx, bottomAnchorHeightPx) {
                snapshotFlow {
                    val bottomAnchor =
                        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == props.messages.size }
                    SessionChatAutoFollowLayoutSnapshot(
                        isPinnedToEnd = isPinnedToEnd,
                        isScrollInProgress = listState.isScrollInProgress,
                        viewportEndOffset = listState.layoutInfo.viewportEndOffset,
                        bottomAnchorOffset = bottomAnchor?.offset,
                        bottomAnchorSize = bottomAnchor?.size,
                    )
                }
                    .distinctUntilChanged()
                    .collect { snapshot ->
                        logScrollD("snapshot count=${props.messages.size} $snapshot")
                        when {
                            snapshot.isScrollInProgress || snapshot.isPinnedToEnd ->
                                autoFollowState.updatePinnedState(
                                    isPinnedToEnd = snapshot.isPinnedToEnd,
                                    messageCount = props.messages.size,
                                )
                            autoFollowState.shouldFollowLayoutChange(
                                autoFollowEnabled = true,
                                isPinnedToEnd = snapshot.isPinnedToEnd,
                            ) -> followLayoutChangeAfterSettled()
                            else ->
                                autoFollowState.updatePinnedState(
                                    isPinnedToEnd = false,
                                    messageCount = props.messages.size,
                                )
                        }
                }
            }

            LaunchedEffect(messageLayoutSignature) {
                logScrollD("layout-signature changed count=${props.messages.size} signature=$messageLayoutSignature")
                if (props.messages.isNotEmpty()) {
                    followLayoutChangeAfterSettled()
                }
            }

            LaunchedEffect(props.messages.firstOrNull()?.id, props.messages.lastOrNull()?.id) {
                if (!initialTimelineEndScrollDone && props.messages.isNotEmpty()) {
                    logScrollD("initial-scroll count=${props.messages.size}")
                    animateToEndAndArmFollow()
                    initialTimelineEndScrollDone = true
                }
            }

            LaunchedEffect(props.messages.size) {
                logScrollD("message-count changed count=${props.messages.size}")
                if (
                    autoFollowState.onMessageCountChanged(
                        messageCount = props.messages.size,
                        autoFollowEnabled = true,
                    )
                ) {
                    animateAppendToEndAndArmFollow()
                }
            }

            Box(modifier = modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = props.isRefreshing,
                    onRefresh = props.onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 12.dp,
                            ),
                    ) {
                        itemsIndexed(props.messages, key = { _, message -> message.id }) { index, message ->
                            SessionChatMessageRow(
                                message = message,
                                onRestoreClick = { props.onRestoreComposerText(message.text) },
                                modifier =
                                    Modifier.padding(
                                        bottom = if (index == props.messages.lastIndex) 0.dp else 8.dp,
                                    ),
                            )
                        }
                        item(key = SessionChatTimelineBottomAnchorKey) {
                            Spacer(modifier = Modifier.height(SessionChatTimelineBottomAnchorHeight))
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showScrollToEnd,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                ) {
                    OpenCodeActionPill(
                        variant = OpenCodeActionPillVariant.ScrollToEnd,
                        onClick = {
                            coroutineScope.launch {
                                logScrollD("pill-click count=${props.messages.size}")
                                animateToEndAndArmFollow()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionChatMessageRow(
    message: SessionChatMessageItem,
    onRestoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
                    onRestoreClick = onRestoreClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            MessageRole.Assistant ->
                AssistantMessageBubble(
                    text = message.text,
                    renderedMarkdown = message.renderedMarkdown,
                )
            MessageRole.Command, MessageRole.Tool, MessageRole.Unknown ->
                FallbackMessageBubble(roleLabel = message.role.displayLabel(), text = message.text)
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

private const val SessionChatTimelineBottomAnchorKey = "session-chat-timeline-bottom"
private val SessionChatTimelineBottomAnchorHeight = 1.dp
private val SessionChatAutoFollowTolerance = 32.dp

private data class SessionChatAutoFollowLayoutSnapshot(
    val isPinnedToEnd: Boolean,
    val isScrollInProgress: Boolean,
    val viewportEndOffset: Int,
    val bottomAnchorOffset: Int?,
    val bottomAnchorSize: Int?,
)

private const val SessionChatScrollLogTag = "SessionChatScroll"

private fun logScrollD(message: String) {
    runCatching { Log.d(SessionChatScrollLogTag, message) }
}

private fun List<SessionChatMessageItem>.layoutSignature(): Int =
    fold(size) { result, message ->
        31 * result +
            message.id.hashCode() +
            message.text.length +
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
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
