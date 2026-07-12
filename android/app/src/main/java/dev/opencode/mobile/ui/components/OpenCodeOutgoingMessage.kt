package dev.opencode.mobile.ui.components

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.theme.OpenCodeTheme

private const val OUTGOING_MESSAGE_MAX_WIDTH_FRACTION = 0.80f
private val OutgoingMessageShape = RoundedCornerShape(8.dp)
private val OutgoingMessagePaddingHorizontal = 12.dp
private val OutgoingMessagePaddingVertical = 8.dp
private val OutgoingMessageFooterSpacing = 6.dp

@Composable
fun OpenCodeOutgoingMessage(
    text: String,
    metadataLabel: String,
    modifier: Modifier = Modifier,
    renderedMarkdown: MarkdownDocument? = null,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val maxBubbleWidth = maxWidth * OUTGOING_MESSAGE_MAX_WIDTH_FRACTION

        Column(
            modifier = Modifier.widthIn(max = maxBubbleWidth),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = maxBubbleWidth),
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
                            modifier = Modifier.widthIn(max = maxBubbleWidth - OutgoingMessagePaddingHorizontal * 2),
                        )
                    } else {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.widthIn(max = maxBubbleWidth - OutgoingMessagePaddingHorizontal * 2),
                        )
                    }
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
                        .padding(top = OutgoingMessageFooterSpacing),
            )
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
