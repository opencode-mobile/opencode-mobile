package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.theme.OpenCodeTheme

private val AssistantMessageCornerRadius = 8.dp
private val AssistantMessagePaddingHorizontal = 12.dp
private val AssistantMessagePaddingVertical = 8.dp
private val AssistantMessageBorderWidth = 1.dp

internal const val ASSISTANT_MESSAGE_EMPTY_PLACEHOLDER = "…"

internal fun assistantMessageDisplayText(text: String): String = text.ifBlank { ASSISTANT_MESSAGE_EMPTY_PLACEHOLDER }

@Composable
fun OpenCodeAssistantMessage(
    text: String,
    modifier: Modifier = Modifier,
    renderedMarkdown: MarkdownDocument? = null,
    isStreaming: Boolean = false,
) {
    val shape = RoundedCornerShape(AssistantMessageCornerRadius)
    val bubbleModifier =
        modifier
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(AssistantMessageBorderWidth, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(
                horizontal = AssistantMessagePaddingHorizontal,
                vertical = AssistantMessagePaddingVertical,
            )

    if (renderedMarkdown != null) {
        OpenCodeRenderedMarkdown(
            document = renderedMarkdown,
            variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
            isStreaming = isStreaming,
            modifier = bubbleModifier,
        )
    } else {
        Text(
            text = assistantMessageDisplayText(text),
            modifier = bubbleModifier,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
