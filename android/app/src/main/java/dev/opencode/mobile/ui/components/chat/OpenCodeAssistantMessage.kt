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

@Composable
fun OpenCodeAssistantMessage(
    text: String,
    modifier: Modifier = Modifier,
    renderedMarkdown: MarkdownDocument? = null,
    isStreaming: Boolean = false,
    presentation: OpenCodeAssistantMessagePresentation = OpenCodeAssistantMessagePresentation.Bubble,
    onTextTap: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(AssistantMessageCornerRadius)
    val bubbleModifier =
        when (presentation) {
            OpenCodeAssistantMessagePresentation.Bubble ->
                modifier
                    .widthIn(max = OpenCodeLayout.AssistantMessageMaxWidth)
                    .background(openCodeColor("surface-raised-base"), shape)
                    .padding(
                        horizontal = AssistantMessagePaddingHorizontal,
                        vertical = AssistantMessagePaddingVertical,
                    )
            OpenCodeAssistantMessagePresentation.Plain ->
                modifier
                    .widthIn(max = OpenCodeLayout.AssistantMessageMaxWidth)
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
