package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DistributedSchedulerReproTest {
    private fun sampleMarkdown(): String =
        DistributedSchedulerReproTest::class.java.classLoader!!
            .getResource("repro/distributed-task-scheduler.md")!!
            .readText()

    @Test
    fun largeMultiLanguageSampleParsesWithValidCodeSpans() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend),
            )

        val document = OpenCodeMarkdownParser.parse(sampleMarkdown(), highlighter)

        assertTrue(document.blocks.isNotEmpty(), "Expected the sample to parse into blocks")

        val codeBlocks = document.blocks.filterIsInstance<MarkdownBlock.CodeBlock>()
        assertTrue(codeBlocks.isNotEmpty(), "Expected at least one fenced code block")

        codeBlocks.forEach { block ->
            val codeLength = block.code.length
            var previousEnd = 0
            block.spans.forEachIndexed { index, span ->
                assertTrue(
                    span.start in 0..codeLength && span.end in span.start..codeLength,
                    "Span $index out of range: $span (codeLen=$codeLength)",
                )
                assertTrue(
                    span.start < span.end,
                    "Span $index is empty or inverted: $span",
                )
                assertTrue(
                    span.start >= previousEnd,
                    "Span $index overlaps the previous span: $span (previousEnd=$previousEnd)",
                )
                previousEnd = span.end
            }
        }
    }

    @Test
    fun iosJsonBridgePreservesBlockStructure() {
        val markdown = sampleMarkdown()

        val direct = OpenCodeMarkdownParser.parse(markdown, OpenCodeSyntaxHighlighter.default)

        val requestJson =
            OpenCodeMarkdownJson.json.encodeToString(
                MarkdownParseRequest(markdown = markdown, variant = "assistantMessage", isStreaming = false),
            )
        val responseJson = OpenCodeMarkdownJson.parseMarkdownJson(requestJson)
        val roundTripped = OpenCodeMarkdownJson.json.decodeFromString<MarkdownDocument>(responseJson)

        assertEquals(
            direct.blocks.size,
            roundTripped.blocks.size,
            "JSON bridge should preserve the parsed block count",
        )
    }
}
