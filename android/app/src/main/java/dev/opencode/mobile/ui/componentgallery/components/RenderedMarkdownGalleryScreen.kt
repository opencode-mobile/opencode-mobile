package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdown
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant

private enum class RenderedMarkdownSample(
    val label: String,
    val variant: OpenCodeRenderedMarkdownVariant,
    val markdown: String,
) {
    Assistant(
        label = "Assistant",
        variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
        markdown =
            """
            ### Plan

            - Keep the chat native.
            - Use the renderer only for formatted text.

            ```kotlin
            val renderer = "native Compose"
            ```
            """.trimIndent(),
    ),
    Outgoing(
        label = "Outgoing",
        variant = OpenCodeRenderedMarkdownVariant.OutgoingMessage,
        markdown =
            """
            Please compare `main` against my branch and summarize:

            | Area | Need |
            | --- | --- |
            | Tests | focused |
            | Risk | low |
            """.trimIndent(),
    ),
    Diff(
        label = "Diff",
        variant = OpenCodeRenderedMarkdownVariant.Standalone,
        markdown =
            """
            ```diff
            @@ SessionChatScreen.kt
            -Text(message.text)
            +OpenCodeRenderedMarkdown(markdown = message.text)
            ```
            """.trimIndent(),
    ),
}

@Composable
fun RenderedMarkdownGalleryScreen(modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableStateOf(RenderedMarkdownSample.Assistant) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RenderedMarkdownSample.entries.forEach { sample ->
                OpenCodeChoiceBadge(
                    label = sample.label,
                    selected = sample == selected,
                    onClick = { selected = sample },
                )
            }
        }

        OpenCodeRenderedMarkdown(
            markdown = selected.markdown,
            variant = selected.variant,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp),
        )
    }
}
