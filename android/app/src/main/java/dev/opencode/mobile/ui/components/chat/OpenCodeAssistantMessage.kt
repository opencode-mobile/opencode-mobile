package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val AssistantMessageCornerRadius = OpenCodeRadius.small
private val AssistantMessagePaddingHorizontal = 12.dp
private val AssistantMessagePaddingVertical = 8.dp

internal const val ASSISTANT_MESSAGE_EMPTY_PLACEHOLDER = "…"

internal fun assistantMessageDisplayText(text: String): String = text.ifBlank { ASSISTANT_MESSAGE_EMPTY_PLACEHOLDER }

/**
 * How the assistant text sits in the timeline: the raised bubble, or plain
 * prose for the chip timeline where the tool chips follow the paragraph.
 */
enum class OpenCodeAssistantMessagePresentation {
    Bubble,
    Plain,
}

private val AssistantMessagePlainPaddingHorizontal = 4.dp

/**
 * Content wider than this share of the row fills the row, so long replies line
 * up with full-width cards instead of ending just short.
 */
internal const val ASSISTANT_MESSAGE_FILL_ROW_THRESHOLD = 0.8f

/**
 * Whether a bubble whose content measures [contentWidth] against
 * [availableWidth] fills the row. Exactly at the threshold it still hugs.
 */
internal fun assistantMessageFillsRow(
    contentWidth: Float,
    availableWidth: Float,
): Boolean {
    if (availableWidth <= 0f || availableWidth.isInfinite()) return false
    return contentWidth > availableWidth * ASSISTANT_MESSAGE_FILL_ROW_THRESHOLD
}

/**
 * Measures the content once at the available width, then reports either its
 * own width or the full row when it passes [ASSISTANT_MESSAGE_FILL_ROW_THRESHOLD].
 * The content stays at its own size on the leading edge; only modifiers before
 * this one (the bubble background) stretch.
 */
private fun Modifier.assistantMessageWidth(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0))
        val fills =
            constraints.hasBoundedWidth &&
                assistantMessageFillsRow(placeable.width.toFloat(), constraints.maxWidth.toFloat())
        val width =
            if (fills) constraints.maxWidth else placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }

@Composable
fun OpenCodeAssistantMessage(
    text: String,
    modifier: Modifier = Modifier,
    renderedMarkdown: MarkdownDocument? = null,
    isStreaming: Boolean = false,
    presentation: OpenCodeAssistantMessagePresentation = OpenCodeAssistantMessagePresentation.Bubble,
    onTextTap: ((OpenCodeMessageTapTarget) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(AssistantMessageCornerRadius)
    val bubbleModifier =
        when (presentation) {
            // The background sits outside the width rule so it covers the
            // full row when the content passes the fill threshold.
            OpenCodeAssistantMessagePresentation.Bubble ->
                modifier
                    .widthIn(max = OpenCodeLayout.AssistantMessageMaxWidth)
                    .background(openCodeColor("surface-raised-base"), shape)
                    .assistantMessageWidth()
                    .padding(
                        horizontal = AssistantMessagePaddingHorizontal,
                        vertical = AssistantMessagePaddingVertical,
                    )
            OpenCodeAssistantMessagePresentation.Plain ->
                modifier
                    .widthIn(max = OpenCodeLayout.AssistantMessageMaxWidth)
                    .assistantMessageWidth()
                    .padding(horizontal = AssistantMessagePlainPaddingHorizontal)
        }

    if (renderedMarkdown != null) {
        OpenCodeRenderedMarkdown(
            document = renderedMarkdown,
            variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
            isStreaming = isStreaming,
            modifier = bubbleModifier,
            onTextTap = onTextTap,
        )
    } else {
        SelectionContainer(modifier = Modifier.opensMessageActionsOnTextTap(onTextTap)) {
            Text(
                text = assistantMessageDisplayText(text),
                modifier = bubbleModifier,
                style = MaterialTheme.typography.bodyLarge,
                color = openCodeColor("text-strong"),
            )
        }
    }
}

@Preview(showBackground = true, name = "Assistant Message Light")
@Composable
private fun OpenCodeAssistantMessagePreviewLight() {
    OpenCodeTheme(darkTheme = false) {
        AssistantMessagePreviewColumn()
    }
}

@Preview(showBackground = true, name = "Assistant Message Dark")
@Composable
private fun OpenCodeAssistantMessagePreviewDark() {
    OpenCodeTheme(darkTheme = true) {
        AssistantMessagePreviewColumn()
    }
}

@Composable
private fun AssistantMessagePreviewColumn() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OpenCodeAssistantMessage(
            text = "The Alleskoenner is the right class for that parcel.",
        )
        OpenCodeAssistantMessage(
            text =
                "Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner " +
                    "package to the smaller parcel options and keep the recommendation practical.",
        )
        OpenCodeAssistantMessage(text = "")
    }
}
