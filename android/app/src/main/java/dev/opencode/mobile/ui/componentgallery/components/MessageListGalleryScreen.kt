package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.mobile.ui.components.OpenCodeActionPill
import dev.opencode.mobile.ui.components.OpenCodeActionPillVariant
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.scroll.animateScrollToTimelineEnd
import dev.opencode.mobile.ui.scroll.animateScrollToTimelineEndAfterAppend
import dev.opencode.mobile.ui.scroll.shouldShowScrollToEndPill
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val MessageListGalleryBottomAnchorKey = "message-list-gallery-bottom"
private val MessageListGalleryAutoFollowTolerance = 16.dp

@Composable
fun MessageListGalleryScreen(
    modifier: Modifier = Modifier,
    viewModel: MessageListGalleryViewModel = viewModel(),
) {
    MessageListGalleryPreview(
        messages = viewModel.messages,
        autoFollowEnabled = viewModel.autoFollowEnabled,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun MessageListGalleryPreview(
    messages: List<MessageListGalleryMessage>,
    autoFollowEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val revealThresholdMinPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    val autoFollowTolerancePx = with(LocalDensity.current) { MessageListGalleryAutoFollowTolerance.roundToPx() }
    val showScrollToEnd by remember(messages, revealThresholdMinPx) {
        derivedStateOf {
            listState.shouldShowScrollToEndPill(
                messageCount = messages.size,
                revealThresholdMinPx = revealThresholdMinPx,
            )
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val autoFollowState = remember { MessageListAutoFollowState(initialMessageCount = messages.size) }

    LaunchedEffect(listState, messages.size, autoFollowTolerancePx) {
        snapshotFlow {
            listState.isPinnedToTimelineEnd(
                messageCount = messages.size,
                tolerancePx = autoFollowTolerancePx,
            )
        }
            .distinctUntilChanged()
            .collect { isPinnedToEnd ->
                autoFollowState.updatePinnedState(
                    isPinnedToEnd = isPinnedToEnd,
                    messageCount = messages.size,
                )
            }
    }

    suspend fun animateToEndAndArmFollow() {
        listState.animateScrollToTimelineEnd(messageCount = messages.size)
        withFrameNanos { }
        autoFollowState.markProgrammaticScrollSettled(
            isPinnedToEnd =
                listState.isPinnedToTimelineEnd(
                    messageCount = messages.size,
                    tolerancePx = autoFollowTolerancePx,
                ),
        )
    }

    suspend fun animateAppendToEndAndArmFollow() {
        withFrameNanos { }
        listState.animateScrollToTimelineEndAfterAppend(messageCount = messages.size)
        withFrameNanos { }
        autoFollowState.markProgrammaticScrollSettled(
            isPinnedToEnd =
                listState.isPinnedToTimelineEnd(
                    messageCount = messages.size,
                    tolerancePx = autoFollowTolerancePx,
                ),
        )
    }

    LaunchedEffect(messages.size, autoFollowEnabled) {
        if (
            autoFollowState.onMessageCountChanged(
                messageCount = messages.size,
                autoFollowEnabled = autoFollowEnabled,
            )
        ) {
            animateAppendToEndAndArmFollow()
        }
    }

    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background),
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
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                MessageListGalleryMessageRow(
                    message = message,
                    modifier =
                        Modifier.padding(
                            bottom = if (index == messages.lastIndex) 0.dp else 8.dp,
                        ),
                )
            }
            item(key = MessageListGalleryBottomAnchorKey) {
                Spacer(modifier = Modifier.height(1.dp))
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
                        animateToEndAndArmFollow()
                    }
                },
            )
        }
    }
}

private fun LazyListState.isPinnedToTimelineEnd(
    messageCount: Int,
    tolerancePx: Int,
): Boolean {
    if (messageCount == 0) return true

    val bottomAnchorIndex = messageCount
    val bottomAnchor =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == bottomAnchorIndex }
            ?: return false

    return bottomAnchor.offset + bottomAnchor.size <= layoutInfo.viewportEndOffset + tolerancePx
}

@Composable
private fun MessageListGalleryMessageRow(
    message: MessageListGalleryMessage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment =
            if (message.role == MessageListGalleryRole.User) {
                Alignment.End
            } else {
                Alignment.Start
            },
    ) {
        when (message.role) {
            MessageListGalleryRole.User ->
                OpenCodeOutgoingMessage(
                    text = message.text,
                    metadataLabel = message.metadataLabel.orEmpty(),
                    onRestoreClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            MessageListGalleryRole.Assistant ->
                Text(
                    text = message.text.ifBlank { "…" },
                    modifier =
                        Modifier
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
        }
    }
}
