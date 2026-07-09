package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.scroll.MessageListReversedTimelineColumn
import dev.opencode.mobile.ui.scroll.MessageListScrollToEndOverlay
import dev.opencode.mobile.ui.scroll.messageListAnimateLastMessageSize
import dev.opencode.mobile.ui.scroll.messageListTimelineItemSpacing
import dev.opencode.mobile.ui.scroll.rememberMessageListTimeline

private const val GALLERY_BOTTOM_ANCHOR_KEY = "message-list-gallery-bottom"

@Composable
fun MessageListGalleryScreen(
    modifier: Modifier = Modifier,
    viewModel: MessageListGalleryViewModel = viewModel(),
) {
    GalleryPreview(
        messages = viewModel.messages,
        autoFollowEnabled = viewModel.autoFollowEnabled,
        onAddMessage = viewModel::addMessage,
        onRemoveLastMessage = viewModel::removeLastMessage,
        onResizeLastMessage = viewModel::resizeLastMessage,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun GalleryPreview(
    messages: List<MessageListGalleryMessage>,
    autoFollowEnabled: Boolean,
    onAddMessage: () -> Unit,
    onRemoveLastMessage: () -> Unit,
    onResizeLastMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeline =
        rememberMessageListTimeline(
            messageCount = messages.size,
            firstMessageId = messages.firstOrNull()?.id,
            lastMessageId = messages.lastOrNull()?.id,
            autoFollowEnabled = autoFollowEnabled,
        )

    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background),
    ) {
        MessageListReversedTimelineColumn(
            timeline = timeline,
            items = messages,
            bottomAnchorKey = GALLERY_BOTTOM_ANCHOR_KEY,
            modifier = Modifier.fillMaxSize(),
            key = { message -> message.id },
        ) { message, isNewestMessage, onContentSizeSettled ->
            GalleryMessageRow(
                message = message,
                animateSizeChanges = isNewestMessage,
                onContentSizeAnimationFinished = onContentSizeSettled,
                modifier = Modifier.messageListTimelineItemSpacing(isNewestMessage),
            )
        }

        MessageListScrollToEndOverlay(
            visible = timeline.showScrollToEnd,
            onClick = {
                timeline.scrollController.animateToEnd(
                    messageCount = timeline.messageCount,
                    tolerancePx = timeline.autoFollowTolerancePx,
                    bottomAnchorHeightPx = timeline.bottomAnchorHeightPx,
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        AutoFollowIndicator(
            thresholdMet = timeline.isPinnedToEnd,
            autoFollowEnabled = autoFollowEnabled,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp),
        )

        FloatingControls(
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
private fun FloatingControls(
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

@Composable
private fun AutoFollowIndicator(
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
private fun GalleryMessageRow(
    message: MessageListGalleryMessage,
    animateSizeChanges: Boolean,
    onContentSizeAnimationFinished: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .messageListAnimateLastMessageSize(
                    animateSizeChanges = animateSizeChanges,
                    onContentSizeAnimationFinished = onContentSizeAnimationFinished,
                ),
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
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(8.dp),
                            ).border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(8.dp),
                            ).padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
        }
    }
}
