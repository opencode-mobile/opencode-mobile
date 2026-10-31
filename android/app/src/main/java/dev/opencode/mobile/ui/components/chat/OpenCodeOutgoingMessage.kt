package dev.opencode.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.command.commandTokenHighlightRanges
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private const val OUTGOING_MESSAGE_MAX_WIDTH_FRACTION = 0.80f
private val OutgoingMessageShape = OpenCodeSmallShape
private val OutgoingMessagePaddingHorizontal = 12.dp
private val OutgoingMessagePaddingVertical = 8.dp
private val OutgoingMessageCornerRadius = dev.opencode.mobile.theme.OpenCodeRadius.small
private const val PENDING_STEER_ALPHA = 0.6f

/**
 * A prompt steered into a running turn. Pending: dimmed with a dashed outline and a
 * caption until the next step delivers it. Either way the first of a run of steers
 * carries the "Steering" label.
 */
sealed interface OpenCodeOutgoingMessageSteering {
    val showsLabel: Boolean

    data class Pending(
        override val showsLabel: Boolean,
    ) : OpenCodeOutgoingMessageSteering

    data class Delivered(
        override val showsLabel: Boolean,
    ) : OpenCodeOutgoingMessageSteering

    companion object {
        const val LABEL = "↱ Steering"
        const val PENDING_CAPTION = "Pending · sends at the next step"
    }
}

sealed interface OpenCodeOutgoingMessageContent {
    data class Text(
        val text: String,
        val renderedMarkdown: MarkdownDocument? = null,
    ) : OpenCodeOutgoingMessageContent

    data class Attachment(
        val presentation: OpenCodeAttachmentPresentation,
    ) : OpenCodeOutgoingMessageContent
}

/**
 * A user message, trailing-aligned. [metadataLabel] (agent, model, time) is read by
 * TalkBack but not drawn; dragging the message left reveals [sentAtEpochMs].
 */
@Composable
fun OpenCodeOutgoingMessage(
    text: String,
    metadataLabel: String,
    modifier: Modifier = Modifier,
    sentAtEpochMs: Long? = null,
    renderedMarkdown: MarkdownDocument? = null,
    attachments: List<OpenCodeAttachmentPresentation> = emptyList(),
    content: List<OpenCodeOutgoingMessageContent>? = null,
    steering: OpenCodeOutgoingMessageSteering? = null,
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
        val time = rememberOpenCodeMessageTime(sentAtEpochMs)
        val accessibilityValue = outgoingMessageAccessibilityValue(metadataLabel, time)
        val isPending = steering is OpenCodeOutgoingMessageSteering.Pending
        val captionColor = openCodeColor("text-weak")
        val dashColor = openCodeColor("text-weaker")

        OpenCodeTimeReveal(time = time) {
            Column(
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (steering?.showsLabel == true) {
                    Text(
                        text = OpenCodeOutgoingMessageSteering.LABEL,
                        style = MaterialTheme.typography.labelMedium,
                        color = captionColor,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                resolvedContent.forEach { part ->
                    when (part) {
                        is OpenCodeOutgoingMessageContent.Text ->
                            Surface(
                                modifier =
                                    Modifier
                                        .widthIn(max = maxBubbleWidth)
                                        .then(if (isPending) Modifier.pendingSteerOutline(dashColor) else Modifier)
                                        // Dims the whole bubble (fill, plain text and markdown); the outline stays full.
                                        .alpha(if (isPending) PENDING_STEER_ALPHA else 1f)
                                        .clip(OutgoingMessageShape)
                                        .then(
                                            outgoingMessageInteractionModifier(
                                                onClick = onClick,
                                                onLongClick = onLongClick,
                                                accessibilityValue = accessibilityValue,
                                            ),
                                        ),
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
                                        val highlights = commandTokenHighlightRanges(part.text)
                                        val highlightColor = openCodeColor("syntax-type")
                                        Text(
                                            text =
                                                if (highlights.isEmpty()) {
                                                    AnnotatedString(part.text)
                                                } else {
                                                    buildAnnotatedString {
                                                        append(part.text)
                                                        highlights.forEach { highlight ->
                                                            addStyle(
                                                                SpanStyle(color = highlightColor),
                                                                highlight.first,
                                                                highlight.last + 1,
                                                            )
                                                        }
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
                                modifier =
                                    Modifier
                                        .widthIn(max = maxBubbleWidth)
                                        .alpha(if (isPending) PENDING_STEER_ALPHA else 1f),
                            )
                    }
                }
                if (isPending) {
                    Text(
                        text = OpenCodeOutgoingMessageSteering.PENDING_CAPTION,
                        style = MaterialTheme.typography.labelMedium,
                        color = captionColor,
                    )
                }
            }
        }
    }
}

/** Dashed outline drawn over the bubble edge. */
private fun Modifier.pendingSteerOutline(color: Color): Modifier =
    drawWithContent {
        drawContent()
        val strokeWidth = 1.dp.toPx()
        val inset = strokeWidth / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = CornerRadius(OutgoingMessageCornerRadius.toPx()),
            style =
                Stroke(
                    width = strokeWidth,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
        )
    }

/**
 * The agent, model and time the drag reveal hides from TalkBack, plus the day when
 * the message is not from today.
 */
fun outgoingMessageAccessibilityValue(
    metadataLabel: String,
    time: OpenCodeMessageTime?,
): String {
    if (metadataLabel.isEmpty()) return time?.spokenLabel.orEmpty()
    return listOfNotNull(time?.day, metadataLabel).joinToString(", ")
}

private fun outgoingMessageInteractionModifier(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    accessibilityValue: String,
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
                    contentDescription = "Outgoing message"
                    if (accessibilityValue.isNotEmpty()) stateDescription = accessibilityValue
                }

        onClick != null ->
            Modifier
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Outgoing message. Shows message actions"
                    if (accessibilityValue.isNotEmpty()) stateDescription = accessibilityValue
                }

        accessibilityValue.isNotEmpty() -> Modifier.semantics { stateDescription = accessibilityValue }

        else -> Modifier
    }

@Preview(showBackground = true)
@Composable
private fun OpenCodeOutgoingMessageShortPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeOutgoingMessage(
            text = "Based on the standard 2026 pricing for DHL in Germany, confirm the package class.",
            metadataLabel = "Build · DeepSeek V4 Flash · 9:05 PM",
            sentAtEpochMs = System.currentTimeMillis(),
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

@Preview(showBackground = true)
@Composable
private fun OpenCodeOutgoingMessageSteeringPreview() {
    OpenCodeTheme(darkTheme = false) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OpenCodeOutgoingMessage(
                text = "Use the staging bucket instead.",
                metadataLabel = "Build · DeepSeek V4 Flash · 9:05 PM",
                steering = OpenCodeOutgoingMessageSteering.Delivered(showsLabel = true),
            )
            OpenCodeOutgoingMessage(
                text = "And skip the migration step.",
                metadataLabel = "Build · DeepSeek V4 Flash · 9:06 PM",
                steering = OpenCodeOutgoingMessageSteering.Pending(showsLabel = false),
            )
        }
    }
}

private val OutgoingMessageLongPreviewText =
    """
    Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options.
    Include the likely dimensions, weight limits, and the tradeoffs a customer should consider before choosing it.
    Keep the answer practical and note where pricing depends on the booking channel or destination.
    Then summarize the recommendation in one concise sentence for a mobile chat user.
    """.trimIndent()
