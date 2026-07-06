package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeActionPill
import dev.opencode.mobile.ui.components.OpenCodeActionPillVariant
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.scroll.MessageListAutoFollowState
import dev.opencode.mobile.ui.scroll.animateScrollToTimelineEnd
import dev.opencode.mobile.ui.scroll.animateScrollToTimelineEndAfterAppend
import dev.opencode.mobile.ui.scroll.isPinnedToTimelineEnd
import dev.opencode.mobile.ui.scroll.shouldShowScrollToEndPill
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val MessageListGalleryBottomAnchorKey = "message-list-gallery-bottom"
private val MessageListGalleryBottomAnchorHeight = 1.dp
private val MessageListGalleryAutoFollowTolerance = 32.dp

@Composable
fun MessageListGalleryScreen(
    modifier: Modifier = Modifier,
    viewModel: MessageListGalleryViewModel = viewModel(),
) {
    MessageListGalleryPreview(
        messages = viewModel.messages,
        autoFollowEnabled = viewModel.autoFollowEnabled,
        onAddMessage = viewModel::addMessage,
        onRemoveLastMessage = viewModel::removeLastMessage,
        onResizeLastMessage = viewModel::resizeLastMessage,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun MessageListGalleryPreview(
    messages: List<MessageListGalleryMessage>,
    autoFollowEnabled: Boolean,
    onAddMessage: () -> Unit,
    onRemoveLastMessage: () -> Unit,
    onResizeLastMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val revealThresholdMinPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    val autoFollowTolerancePx = with(LocalDensity.current) { MessageListGalleryAutoFollowTolerance.roundToPx() }
    val bottomAnchorHeightPx = with(LocalDensity.current) { MessageListGalleryBottomAnchorHeight.roundToPx() }
    val autoFollowThresholdMet by remember(messages.size, autoFollowTolerancePx, bottomAnchorHeightPx) {
        derivedStateOf {
            listState.isPinnedToTimelineEnd(
                messageCount = messages.size,
                tolerancePx = autoFollowTolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
            )
        }
    }
    val showScrollToEnd by remember(messages, revealThresholdMinPx) {
        derivedStateOf {
            listState.shouldShowScrollToEndPill(
                messageCount = messages.size,
                revealThresholdMinPx = revealThresholdMinPx,
            )
        }
    }
    val messageLayoutSignature = remember(messages) { messages.layoutSignature() }
    val coroutineScope = rememberCoroutineScope()
    val autoFollowState = remember { MessageListAutoFollowState(initialMessageCount = messages.size) }

    LaunchedEffect(listState, messages.size, autoFollowTolerancePx, bottomAnchorHeightPx) {
        snapshotFlow { autoFollowThresholdMet }
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
                    bottomAnchorHeightPx = bottomAnchorHeightPx,
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
                    bottomAnchorHeightPx = bottomAnchorHeightPx,
                ),
        )
    }

    suspend fun followLayoutChangeAfterSettled() {
        withFrameNanos { }
        withFrameNanos { }

        val pinned =
            listState.isPinnedToTimelineEnd(
                messageCount = messages.size,
                tolerancePx = autoFollowTolerancePx,
                bottomAnchorHeightPx = bottomAnchorHeightPx,
            )

        when {
            listState.isScrollInProgress || pinned ->
                autoFollowState.updatePinnedState(
                    isPinnedToEnd = pinned,
                    messageCount = messages.size,
                )
            autoFollowState.shouldFollowLayoutChange(
                autoFollowEnabled = autoFollowEnabled,
                isPinnedToEnd = false,
            ) -> animateAppendToEndAndArmFollow()
            else ->
                autoFollowState.updatePinnedState(
                    isPinnedToEnd = false,
                    messageCount = messages.size,
                )
        }
    }

    LaunchedEffect(messageLayoutSignature, autoFollowEnabled) {
        if (messages.isNotEmpty()) {
            followLayoutChangeAfterSettled()
        }
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
                Spacer(modifier = Modifier.height(MessageListGalleryBottomAnchorHeight))
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

        MessageListAutoFollowIndicator(
            thresholdMet = autoFollowThresholdMet,
            autoFollowEnabled = autoFollowEnabled,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp),
        )

        MessageListGalleryFloatingControls(
            canRemove = messages.isNotEmpty(),
            onAddMessage = onAddMessage,
            onRemoveLastMessage = onRemoveLastMessage,
            onResizeLastMessage = onResizeLastMessage,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
        )
    }
}

@Composable
private fun MessageListGalleryFloatingControls(
    canRemove: Boolean,
    onAddMessage: () -> Unit,
    onRemoveLastMessage: () -> Unit,
    onResizeLastMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(999.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText("+"),
            onClick = onAddMessage,
            contrast = OpenCodeButtonContrast.Low,
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText("-"),
            onClick = onRemoveLastMessage,
            enabled = canRemove,
            contrast = OpenCodeButtonContrast.Low,
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText("/"),
            onClick = onResizeLastMessage,
            enabled = canRemove,
            contrast = OpenCodeButtonContrast.Low,
        )
    }
}

private fun List<MessageListGalleryMessage>.layoutSignature(): Int =
    fold(size) { result, message ->
        31 * result +
            message.id.hashCode() +
            message.text.length
    }

@Composable
private fun MessageListAutoFollowIndicator(
    thresholdMet: Boolean,
    autoFollowEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val label =
        when {
            thresholdMet && autoFollowEnabled -> "Auto-follow armed"
            thresholdMet -> "Threshold met"
            else -> "Threshold missed"
        }

    Text(
        text = label,
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(999.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color =
            if (thresholdMet) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
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
