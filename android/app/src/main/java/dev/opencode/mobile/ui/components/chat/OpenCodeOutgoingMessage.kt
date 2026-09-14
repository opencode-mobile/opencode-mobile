package dev.opencode.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.command.commandTokenHighlightRange
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private const val OUTGOING_MESSAGE_MAX_WIDTH_FRACTION = 0.80f
private val OutgoingMessageShape = RoundedCornerShape(8.dp)
private val OutgoingMessagePaddingHorizontal = 12.dp
private val OutgoingMessagePaddingVertical = 8.dp
private val OutgoingMessageFooterSpacing = 4.dp

sealed interface OpenCodeOutgoingMessageContent {
    data class Text(
        val text: String,
        val renderedMarkdown: MarkdownDocument? = null,
    ) : OpenCodeOutgoingMessageContent

    data class Attachment(
        val presentation: OpenCodeAttachmentPresentation,
    ) : OpenCodeOutgoingMessageContent
}

@Composable
fun OpenCodeOutgoingMessage(
    text: String,
    metadataLabel: String,
    modifier: Modifier = Modifier,
    renderedMarkdown: MarkdownDocument? = null,
    attachments: List<OpenCodeAttachmentPresentation> = emptyList(),
    content: List<OpenCodeOutgoingMessageContent>? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val maxBubbleWidth = maxWidth * OUTGOING_MESSAGE_MAX_WIDTH_FRACTION
        val resolvedContent =
            content ?: buildList {
                if (text.isNotBlank()) add(OpenCodeOutgoingMessageContent.Text(text, renderedMarkdown))
                attachments.forEach { add(OpenCodeOutgoingMessageContent.Attachment(it)) }
            }
        val bubbleColor = openCodeColor("surface-interactive-weak")
        val bubbleTextColor = openCodeColor("text-strong")

        Column(
            modifier = Modifier.widthIn(max = maxBubbleWidth),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            resolvedContent.forEach { part ->
                when (part) {
                    is OpenCodeOutgoingMessageContent.Text ->
                        Surface(
                            modifier =
                                Modifier
                                    .widthIn(max = maxBubbleWidth)
                                    .clip(OutgoingMessageShape)
                                    .then(outgoingMessageInteractionModifier(onClick, onLongClick)),
                            shape = OutgoingMessageShape,
                            color = bubbleColor,
                            contentColor = bubbleTextColor,
                        ) {
                            Column(
                                modifier =
                                    Modifier.padding(
                                        horizontal = OutgoingMessagePaddingHorizontal,
                                        vertical = OutgoingMessagePaddingVertical,
                                    ),
                                horizontalAlignment = Alignment.End,
                            ) {
                                if (part.renderedMarkdown != null) {
                                    OpenCodeRenderedMarkdown(
                                        document = part.renderedMarkdown,
                                        variant = OpenCodeRenderedMarkdownVariant.OutgoingMessage,
                                        modifier = Modifier.widthIn(max = maxBubbleWidth - OutgoingMessagePaddingHorizontal * 2),
                                    )
                                } else {
                                    val highlight = commandTokenHighlightRange(part.text)
                                    val highlightColor = openCodeColor("syntax-type")
                                    Text(
                                        text =
                                            if (highlight == null) {
                                                AnnotatedString(part.text)
                                            } else {
                                                buildAnnotatedString {
                                                    append(part.text)
                                                    addStyle(
                                                        SpanStyle(color = highlightColor),
                                                        highlight.first,
                                                        highlight.last + 1,
                                                    )
                                                }
                                            },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = bubbleTextColor,
                                        overflow = TextOverflow.Clip,
                                        modifier = Modifier.widthIn(max = maxBubbleWidth - OutgoingMessagePaddingHorizontal * 2),
                                    )
                                }
                            }
                        }

                    is OpenCodeOutgoingMessageContent.Attachment ->
                        OpenCodeMessageAttachment(
                            attachment = part.presentation,
                            modifier = Modifier.widthIn(max = maxBubbleWidth),
                        )
                }
            }

            Text(
                text = metadataLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier =
                    Modifier
                        .widthIn(max = maxBubbleWidth)
                        .padding(top = if (resolvedContent.isEmpty()) 0.dp else OutgoingMessageFooterSpacing),
            )
        }
    }
}

private fun outgoingMessageInteractionModifier(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
): Modifier =
    when {
        onLongClick != null ->
            Modifier
                .combinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                    onLongClickLabel = "Show message actions",
                    role = Role.Button,
                ).semantics {
                    contentDescription = "Outgoing message. Long press to show message actions"
                }

        onClick != null ->
            Modifier
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Outgoing message. Shows message actions"
                }

        else -> Modifier
    }

@Preview(showBackground = true)
@Composable
private fun OpenCodeOutgoingMessageShortPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeOutgoingMessage(
            text = "Based on the standard 2026 pricing for DHL in Germany, confirm the package class.",
            metadataLabel = "Build · DeepSeek V4 Flash · 9:05 PM",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeOutgoingMessageLongPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeOutgoingMessage(
            text = OutgoingMessageLongPreviewText,
            metadataLabel = "Build · DeepSeek V4 Flash · 9:05 PM",
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
