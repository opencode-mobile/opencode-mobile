package dev.opencode.mobile.markdown

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpenCodeMarkdownParserTest {
    @Test
    fun detectorRendersBlockAndInlineMarkdown() {
        assertTrue(OpenCodeMarkdownDetector.shouldRender("# Heading"))
        assertTrue(OpenCodeMarkdownDetector.shouldRender("- Item"))
        assertTrue(OpenCodeMarkdownDetector.shouldRender("Use `inline code` here."))
        assertTrue(OpenCodeMarkdownDetector.shouldRender("Read [the docs](https://example.com)."))
        assertTrue(OpenCodeMarkdownDetector.shouldRender("This is **important**."))
        assertFalse(OpenCodeMarkdownDetector.shouldRender("Plain text stays native."))
        assertFalse(OpenCodeMarkdownDetector.shouldRender("First paragraph.\n\nSecond paragraph."))
    }

    @Test
    fun parsesCodeAndDiffBlocks() {
        val document =
            OpenCodeMarkdownParser.parse(
                """
                ```kotlin
                val answer = 42
                ```

                ```diff
                +added
                -deleted
                ```
                """.trimIndent(),
            )

        assertIs<MarkdownBlock.CodeBlock>(document.blocks[0])
        assertIs<MarkdownBlock.DiffBlock>(document.blocks[1])
        val code = document.blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertTrue(code.spans.any { it.role == CodeTokenRole.Keyword })
        val diff = document.blocks[1] as MarkdownBlock.DiffBlock
        assertEquals(DiffLineKind.Added, diff.lines[0].kind)
        assertEquals(DiffLineKind.Deleted, diff.lines[1].kind)
    }

    @Test
    fun parsesCodeBlocksWithInjectedHighlighter() {
        val document =
            OpenCodeMarkdownParser.parse(
                """
                ```sample
                custom token
                ```
                """.trimIndent(),
                codeHighlighter =
                    object : OpenCodeCodeHighlighter {
                        override fun supportsLanguage(languageOrAlias: String?): Boolean = languageOrAlias == "sample"

                        override fun highlight(
                            languageOrAlias: String?,
                            code: String,
                        ): SyntaxHighlightResult =
                            SyntaxHighlightResult(
                                spans = listOf(CodeSpan(start = 0, end = 6, role = CodeTokenRole.Critical)),
                                diagnostics = emptyList(),
                            )
                    },
            )

        val code = assertIs<MarkdownBlock.CodeBlock>(document.blocks.single())
        assertEquals(listOf(CodeSpan(start = 0, end = 6, role = CodeTokenRole.Critical)), code.spans)
    }

    @Test
    fun blocksRawHtmlAndImagesAsDiagnostics() {
        val document = OpenCodeMarkdownParser.parse("""Hello <script>nope</script> ![alt](https://example.com/a.png)""")

        assertTrue(document.diagnostics.any { it.kind == MarkdownDiagnosticKind.RawHtmlLiteral })
        assertTrue(document.diagnostics.any { it.kind == MarkdownDiagnosticKind.ImageOmitted })
        val paragraph = assertIs<MarkdownBlock.Paragraph>(document.blocks.first())
        assertTrue(paragraph.inlines.any { it is MarkdownInline.ImageAlt })
    }

    @Test
    fun parsesTables() {
        val document =
            OpenCodeMarkdownParser.parse(
                """
                | A | B |
                |---|---|
                | 1 | `two` |
                """.trimIndent(),
            )

        val table = assertIs<MarkdownBlock.Table>(document.blocks.first())
        assertEquals(2, table.header.cells.size)
        assertEquals(1, table.rows.size)
        assertTrue(table.rows.first().cells[1].inlines.first() is MarkdownInline.Code)
    }

    @Test
    fun markdownFenceClosesOnFirstBareFenceLikeCommonMark() {
        // Match opencode web: an outer ```markdown fence closes at the first
        // bare closing fence (after nested ```bash), so later content is real
        // markdown — not one giant markdown CodeBlock.
        val document =
            OpenCodeMarkdownParser.parse(
                """
                ```markdown
                # Project Documentation

                ```bash
                curl -X GET /api/users/123
                ```

                ## Configuration

                ```json
                {
                  "username": "newuser"
                }
                ```

                After the sample.
                """.trimIndent(),
            )

        val markdownSample = assertIs<MarkdownBlock.CodeBlock>(document.blocks[0])
        assertEquals("markdown", markdownSample.language)
        assertTrue(markdownSample.code.contains("# Project Documentation"))
        assertTrue(markdownSample.code.contains("```bash"))
        assertTrue(markdownSample.code.contains("curl -X GET /api/users/123"))
        assertFalse(markdownSample.code.contains("## Configuration"))

        val heading = assertIs<MarkdownBlock.Heading>(document.blocks[1])
        assertEquals(2, heading.level)

        val json = assertIs<MarkdownBlock.CodeBlock>(document.blocks[2])
        assertEquals("json", json.language)
        assertTrue(json.code.contains("\"username\""))

        val paragraph = assertIs<MarkdownBlock.Paragraph>(document.blocks[3])
        assertEquals("After the sample.", plainTextFromMarkdownInlines(paragraph.inlines))
    }

    @Test
    fun jsonBoundaryUsesVersionedRenderTreeSchema() {
        val requestJson =
            OpenCodeMarkdownJson.json.encodeToString(
                MarkdownParseRequest(
                    markdown =
                        """
                        ### Title

                        | A | B |
                        |---|---|
                        | 1 | `two` |

                        ```diff
                        +added
                        -deleted
                        ```
                        """.trimIndent(),
                    variant = "standalone",
                ),
            )

        val responseJson = OpenCodeMarkdownJson.parseMarkdownJson(requestJson)
        val document = Json.parseToJsonElement(responseJson).jsonObject
        val blocks = document.getValue("blocks").jsonArray

        assertEquals(1, document.getValue("renderTreeVersion").jsonPrimitive.int)
        assertEquals("heading", blocks[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(3, blocks[0].jsonObject.getValue("level").jsonPrimitive.int)
        assertEquals("table", blocks[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("diffBlock", blocks[2].jsonObject.getValue("type").jsonPrimitive.content)

        val diffLines = blocks[2].jsonObject.getValue("lines").jsonArray
        assertEquals("Added", diffLines[0].jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("Deleted", diffLines[1].jsonObject.getValue("kind").jsonPrimitive.content)
    }

    @Test
    fun openCodeCodeHighlighterForParseDefersTextMateWhileStreaming() {
        val preferred =
            object : OpenCodeCodeHighlighter {
                override fun supportsLanguage(languageOrAlias: String?): Boolean = true

                override fun highlight(
                    languageOrAlias: String?,
                    code: String,
                ): SyntaxHighlightResult =
                    SyntaxHighlightResult(
                        spans = listOf(CodeSpan(0, code.length, CodeTokenRole.Keyword)),
                        diagnostics = emptyList(),
                    )
            }

        val streamingHighlighter =
            openCodeCodeHighlighterForParse(preferred, isStreaming = true, textMateEnabled = true)
        val finalHighlighter =
            openCodeCodeHighlighterForParse(preferred, isStreaming = false, textMateEnabled = true)
        val textMateDisabled =
            openCodeCodeHighlighterForParse(preferred, isStreaming = false, textMateEnabled = false)

        val code = "val answer = 42"
        val streamingResult = streamingHighlighter.highlight("kotlin", code)
        val finalResult = finalHighlighter.highlight("kotlin", code)
        val disabledResult = textMateDisabled.highlight("kotlin", code)

        assertNotEquals(finalResult.spans, streamingResult.spans)
        assertEquals(CodeTokenRole.Keyword, finalResult.spans.single().role)
        assertEquals(streamingResult.spans, disabledResult.spans)

        val structureOnly =
            openCodeCodeHighlighterForParse(
                preferred,
                isStreaming = true,
                textMateEnabled = true,
                structureOnly = true,
            )
        val structureResult = structureOnly.highlight("kotlin", code)
        assertTrue(structureResult.spans.isEmpty())
        assertEquals(SyntaxHighlightSource.StructureOnly, structureResult.source)
    }

    @Test
    fun structureOnlyParseEmitsCodeBlocksWithoutSpans() {
        val document =
            OpenCodeMarkdownParser.parse(
                markdown =
                    """
                    ```javascript
                    const x = 1
                    ```
                    """.trimIndent(),
                structureOnly = true,
            )
        val code = document.blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("javascript", code.language)
        assertTrue(code.spans.isEmpty())
    }

    @Test
    fun streamingJsonBoundaryUsesSemanticFallbackHighlighter() {
        val requestJson =
            OpenCodeMarkdownJson.json.encodeToString(
                MarkdownParseRequest(
                    markdown = "```kotlin\nval answer = 42\n```",
                    isStreaming = true,
                ),
            )

        val responseJson = OpenCodeMarkdownJson.parseMarkdownJson(requestJson)
        val document = Json.parseToJsonElement(responseJson).jsonObject

        assertEquals("codeBlock", document.getValue("blocks").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun plainTextFromInlinesMatchesReadableFallback() {
        val inlines =
            listOf(
                MarkdownInline.Text("Read "),
                MarkdownInline.Link(
                    destination = "https://example.com",
                    children = listOf(MarkdownInline.Text("docs")),
                ),
                MarkdownInline.Text(" and use "),
                MarkdownInline.Code("inline"),
            )

        assertEquals("Read docs and use inline", plainTextFromMarkdownInlines(inlines))
    }

    @Test
    fun stableMarkdownSourceHashDiffersForDifferentSnapshots() {
        assertNotEquals(
            stableMarkdownSourceHash("# Aa"),
            stableMarkdownSourceHash("# BB"),
        )
    }
}
