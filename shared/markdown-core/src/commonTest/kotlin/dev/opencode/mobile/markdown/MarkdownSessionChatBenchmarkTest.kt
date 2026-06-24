package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownSessionChatBenchmarkTest {
    @Test
    fun benchmarkLongMarkdownHistoryHybridBackend() {
        val bundle =
            OpenCodeTextMateGrammarBundleLoader
                .loadBundled(
                    OpenCodeTextMateResourceReader { resourcePath ->
                        MarkdownSessionChatBenchmarkTest::class.java.classLoader
                            ?.getResource(resourcePath)
                            ?.readText()
                    },
                ).bundle
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundle,
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeOnigurumaRegexBackend),
            )
        val messages = longMarkdownHistory()

        val detectorStartedAt = System.nanoTime()
        val markdownCount = messages.count { OpenCodeMarkdownDetector.shouldRender(it) }
        val detectorMs = (System.nanoTime() - detectorStartedAt) / 1_000_000

        val parserStartedAt = System.nanoTime()
        messages.forEach { message ->
            if (OpenCodeMarkdownDetector.shouldRender(message)) {
                OpenCodeMarkdownParser.parse(message, highlighter)
            }
        }
        val parserMs = (System.nanoTime() - parserStartedAt) / 1_000_000

        assertEquals(20, markdownCount)
        println(
            "MARKDOWN_BENCHMARK kmp-hybrid detector-ms=$detectorMs parser-ms=$parserMs messages=20 code-lines=3000",
        )
    }

    private fun longMarkdownHistory(): List<String> =
        (1..20).map { messageIndex ->
            buildString {
                append("## Response $messageIndex\n\n```typescript\n")
                repeat(150) { lineIndex ->
                    append("const item$lineIndex = response$lineIndex?.value ?? 'markdown benchmark';\n")
                }
                append("```\n")
            }
        }
}
