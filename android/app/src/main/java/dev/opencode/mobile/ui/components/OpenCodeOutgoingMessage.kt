package dev.opencode.mobile.ui.components

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private const val OUTGOING_MESSAGE_COLLAPSED_LINE_COUNT = 5
private const val OUTGOING_MESSAGE_MAX_WIDTH_FRACTION = 0.84f
private val OutgoingMessageCollapsedRenderedMarkdownHeight = 140.dp
private val OutgoingMessageShape = RoundedCornerShape(8.dp)
private val OutgoingMessagePaddingHorizontal = 18.dp
private val OutgoingMessagePaddingVertical = 14.dp
private val OutgoingMessageContentSpacing = 4.dp
private val OutgoingMessageFooterSpacing = 6.dp
private val OutgoingMessageControlItemSpacing = 8.dp
private val OutgoingMessageActionIconTouchTarget = 28.dp
private val OutgoingMessageActionIconSize = 16.dp
private val OutgoingMessageChevronIconSize = 16.dp

@Composable
fun OpenCodeOutgoingMessage(
    text: String,
    metadataLabel: String,
    onRestoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    renderedMarkdown: dev.opencode.mobile.markdown.MarkdownDocument? = null,
    initiallyExpanded: Boolean = false,
    initialRenderedMarkdownHeight: Dp = 1.dp,
    onRenderedMarkdownHeightChange: (Dp) -> Unit = {},
) {
    var expanded by rememberSaveable(text) { mutableStateOf(initiallyExpanded) }
    var expandable by rememberSaveable(text) { mutableStateOf(false) }
    val context = LocalContext.current

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier.width(maxWidth * OUTGOING_MESSAGE_MAX_WIDTH_FRACTION),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = OutgoingMessageShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier =
                        Modifier.padding(
                            horizontal = OutgoingMessagePaddingHorizontal,
                            vertical = OutgoingMessagePaddingVertical,
                        ),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (renderedMarkdown != null) {
                        OpenCodeRenderedMarkdown(
                            document = renderedMarkdown,
                            variant = OpenCodeRenderedMarkdownVariant.OutgoingMessage,
                            initialHeight = initialRenderedMarkdownHeight,
                            collapsedMaxHeight = OutgoingMessageCollapsedRenderedMarkdownHeight,
                            expanded = expanded,
                            onExpandableChange = { expandable = it },
                            onContentHeightChange = { height ->
                                onRenderedMarkdownHeightChange(minOf(height, OutgoingMessageCollapsedRenderedMarkdownHeight))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = if (expanded) Int.MAX_VALUE else OUTGOING_MESSAGE_COLLAPSED_LINE_COUNT,
                            overflow = TextOverflow.Clip,
                            onTextLayout = { result ->
                                expandable =
                                    if (expanded) {
                                        result.lineCount > OUTGOING_MESSAGE_COLLAPSED_LINE_COUNT
                                    } else {
                                        result.hasVisualOverflow || result.lineCount > OUTGOING_MESSAGE_COLLAPSED_LINE_COUNT
                                    }
                            },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = OutgoingMessageContentSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (expandable) {
                            OutgoingMessageIconButton(
                                contentDescription = if (expanded) "Collapse message" else "Expand message",
                                onClick = { expanded = !expanded },
                            ) {
                                Icon(
                                    imageVector = if (expanded) OpenCodeIcons.CollapseContent else OpenCodeIcons.ExpandContent,
                                    contentDescription = null,
                                    modifier = Modifier.size(OutgoingMessageChevronIconSize),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        OutgoingMessageIconButton(
                            contentDescription = "Restore message to composer",
                            onClick = onRestoreClick,
                            modifier = Modifier.padding(start = OutgoingMessageControlItemSpacing),
                        ) {
                            Icon(
                                imageVector = OpenCodeIcons.Undo,
                                contentDescription = null,
                                modifier = Modifier.size(OutgoingMessageActionIconSize),
                            )
                        }
                        OutgoingMessageIconButton(
                            contentDescription = "Copy message",
                            onClick = {
                                val clipboardManager =
                                    context.getSystemService(
                                        Context.CLIPBOARD_SERVICE,
                                    ) as android.content.ClipboardManager
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("Message", text))
                            },
                            modifier = Modifier.padding(start = OutgoingMessageControlItemSpacing),
                        ) {
                            Icon(
                                imageVector = OpenCodeIcons.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(OutgoingMessageActionIconSize),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OutgoingMessageFooterSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = metadataLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OutgoingMessageIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier =
            modifier
                .size(OutgoingMessageActionIconTouchTarget)
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.semantics { }) {
                content()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeOutgoingMessageShortPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeOutgoingMessage(
            text = "Based on the standard 2026 pricing for DHL in Germany, confirm the package class.",
            metadataLabel = "Build · DeepSeek V4 Flash · 9:05 PM",
            onRestoreClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeOutgoingMessageCollapsedPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeOutgoingMessage(
            text = OutgoingMessageLongPreviewText,
            metadataLabel = "Build · DeepSeek V4 Flash · 9:05 PM",
            onRestoreClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeOutgoingMessageExpandedPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeOutgoingMessage(
            text = OutgoingMessageLongPreviewText,
            metadataLabel = "Build · DeepSeek V4 Flash · 9:05 PM",
            initiallyExpanded = true,
            onRestoreClick = {},
        )
    }
}

private val OutgoingMessageLongPreviewText =
    """
    Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options.
    Include the likely dimensions, weight limits, and the tradeoffs a customer should consider before choosing it.
    Keep the answer practical and note where pricing depends on the booking channel or destination.
    Then summarize the recommendation in one concise sentence for a mobile chat user.
    """.trimIndent()
