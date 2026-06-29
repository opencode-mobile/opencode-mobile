package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.components.OpenCodeOutgoingMessage
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.displayLabel

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
        SessionChatTimelineState.Ready ->
            PullToRefreshBox(
                isRefreshing = props.isRefreshing,
                onRefresh = props.onRefresh,
                modifier = modifier.fillMaxWidth(),
            ) {
                LazyColumn(
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
