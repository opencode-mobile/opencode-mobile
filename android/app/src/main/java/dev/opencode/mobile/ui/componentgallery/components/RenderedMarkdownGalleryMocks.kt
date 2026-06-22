package dev.opencode.mobile.ui.componentgallery.components

import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.markdown.OpenCodeBundledJoniMarkdownHighlighter
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.ui.components.OpenCodeRenderedMarkdownVariant

data class RenderedMarkdownGalleryMock(
    val id: String,
    val label: String,
    val variant: OpenCodeRenderedMarkdownVariant,
    val rawMarkdown: String,
    val document: MarkdownDocument,
    val usesOutgoingMessage: Boolean = false,
)

object RenderedMarkdownGalleryMocks {
    private val codeHighlighter = OpenCodeBundledJoniMarkdownHighlighter.default()

    private val assistantMarkdown =
        """
        ### Release checklist

        The session is ready for review. Start with the **native render tree**,
        then verify the [session status](https://example.com/session-status).

        - Preserve raw markdown for copy and restore.
        - Keep `http` and `https` links native-owned.

        ```kotlin
        val renderer = NativeMarkdownRenderer(platform = Platform.Android)
        renderer.render(document)
        ```
        """.trimIndent()

    private val outgoingMarkdown =
        """
        Please review the markdown migration before merging. The response should
        call out any regression in `SessionChat`, confirm that `diff` blocks keep
        horizontal scrolling, and include the exact test command to rerun after
        resolving comments. This intentionally long formatted paragraph verifies
        that an outgoing inline-markdown message remains fully readable.
        """.trimIndent()

    private val codeMarkdown =
        """
        ```typescript
        export function renderSessionMarkdown(source: string): RenderTree {
          return parseMarkdown(source, { highlight: "textmate", allowHtml: false })
        }
        ```
        """.trimIndent()

    private val languagesMarkdown =
        """
        ### Syntax language sweep

        ```kotlin
        data class Session(val id: String, val active: Boolean)
        ```

        ```swift
        let status = sessions.first(where: \.isActive)
        ```

        ```typescript
        const retry = async <T>(work: () => Promise<T>): Promise<T> => work()
        ```

        ```python
        def summarize(messages: list[str]) -> str:
            return "\\n".join(messages)
        ```

        ```json
        { "renderer": "native", "network": false }
        ```

        ```bash
        ./gradlew :app:assembleDebug
        ```

        ```yaml
        markdown:
          renderer: native
          streaming: debounce
        ```
        """.trimIndent()

    private val richMarkdown =
        """
        ## Review decision

        > Ship the native renderer only when the message stays readable on a
        > narrow screen and links remain platform-owned.

        1. Verify **assistant** and _outgoing_ variants.
        2. Check `diff` colors in light and dark themes.
        3. Open the [release checklist](https://example.com/release-checklist).

        ![Mobile session screenshot](https://example.com/session.png)

        ---

        The image is represented as safe alt text; no remote content is loaded.
        """.trimIndent()

    private val tableMarkdown =
        """
        ### Verification matrix

        | Surface | Expected result | Owner |
        | --- | --- | --- |
        | Session Chat | Native text and code blocks | Mobile |
        | Streaming | No forced scroll after user scrolls up | Mobile |
        | Links | Only HTTPS leaves the app | Platform |
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
                id = "assistant",
                label = "Assistant",
                variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
                rawMarkdown = assistantMarkdown,
                document = OpenCodeMarkdownParser.parse(assistantMarkdown, codeHighlighter = codeHighlighter),
            ),
            RenderedMarkdownGalleryMock(
                id = "request",
                label = "Request",
                variant = OpenCodeRenderedMarkdownVariant.OutgoingMessage,
                rawMarkdown = outgoingMarkdown,
                document = OpenCodeMarkdownParser.parse(outgoingMarkdown, codeHighlighter = codeHighlighter),
                usesOutgoingMessage = true,
            ),
            RenderedMarkdownGalleryMock(
                id = "code",
                label = "Code",
                variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
                rawMarkdown = codeMarkdown,
                document = OpenCodeMarkdownParser.parse(codeMarkdown, codeHighlighter = codeHighlighter),
            ),
            RenderedMarkdownGalleryMock(
                id = "languages",
                label = "Languages",
                variant = OpenCodeRenderedMarkdownVariant.Standalone,
                rawMarkdown = languagesMarkdown,
                document = OpenCodeMarkdownParser.parse(languagesMarkdown, codeHighlighter = codeHighlighter),
            ),
            RenderedMarkdownGalleryMock(
                id = "rich",
                label = "Rich",
                variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
                rawMarkdown = richMarkdown,
                document = OpenCodeMarkdownParser.parse(richMarkdown, codeHighlighter = codeHighlighter),
            ),
            RenderedMarkdownGalleryMock(
                id = "table",
                label = "Table",
                variant = OpenCodeRenderedMarkdownVariant.AssistantMessage,
                rawMarkdown = tableMarkdown,
                document = OpenCodeMarkdownParser.parse(tableMarkdown, codeHighlighter = codeHighlighter),
            ),
            RenderedMarkdownGalleryMock(
                id = "diff",
                label = "Diff",
                variant = OpenCodeRenderedMarkdownVariant.Standalone,
                rawMarkdown = diffMarkdown,
                document = OpenCodeMarkdownParser.parse(diffMarkdown, codeHighlighter = codeHighlighter),
            ),
        )
}
