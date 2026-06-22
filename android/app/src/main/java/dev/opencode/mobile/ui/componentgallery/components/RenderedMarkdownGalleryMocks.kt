package dev.opencode.mobile.ui.componentgallery.components

import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeBundledJoniMarkdownHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant

data class RenderedMarkdownGalleryMock(
    val label: String,
    val variant: OpenCodeRenderedMarkdownVariant,
    val document: MarkdownDocument,
)

object RenderedMarkdownGalleryMocks {
    private val codeHighlighter = OpenCodeBundledJoniMarkdownHighlighter.default()

    private val assistantMarkdown =
        """
        ### Plan

        - Keep the chat native.
        - Use the renderer only for formatted text.

        ```kotlin
        val renderer = "native Compose"
        ```
        """.trimIndent()

    private val outgoingMarkdown =
        """
        Please compare `main` against my branch and summarize:

        | Area | Need |
        | --- | --- |
        | Tests | focused |
        | Risk | low |
        """.trimIndent()

    private val diffMarkdown =
        """
        ```diff
        @@ SessionChatScreen.kt
        -Text(message.text)
        +OpenCodeRenderedMarkdown(document = message.renderedMarkdown)
        ```
        """.trimIndent()

    val samples: List<RenderedMarkdownGalleryMock> =
        listOf(
            RenderedMarkdownGalleryMock(
                label = "Assistant",
                variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
                document = OpenCodeMarkdownParser.parse(assistantMarkdown, codeHighlighter = codeHighlighter),
            ),
            RenderedMarkdownGalleryMock(
                label = "Outgoing",
                variant = OpenCodeRenderedMarkdownVariant.OutgoingMessage,
                document = OpenCodeMarkdownParser.parse(outgoingMarkdown, codeHighlighter = codeHighlighter),
            ),
            RenderedMarkdownGalleryMock(
                label = "Diff",
                variant = OpenCodeRenderedMarkdownVariant.Standalone,
                document = OpenCodeMarkdownParser.parse(diffMarkdown, codeHighlighter = codeHighlighter),
            ),
        )
}
