package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownSessionChatBenchmarkTest {
    @Test
    fun benchmarkLongMarkdownHistoryHybridBackend() {
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeKotlinRegexHybridTextMateBackend),
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

    @Test
    fun benchmarkLongMarkdownHistoryJoniBackend() {
        val metrics = RecordingOpenCodeTextMateMetricsSink()
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    OpenCodeTextMateGrammarBundleLoader
                        .loadBundled(bundledTestResourceReader(), metrics)
                        .bundle,
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexRuntime(), metrics),
                metricsSink = metrics,
            )
        val messages = longMarkdownHistory()

        val parserStartedAt = System.nanoTime()
        messages.forEach { message ->
            if (OpenCodeMarkdownDetector.shouldRender(message)) {
                OpenCodeMarkdownParser.parse(message, highlighter)
            }
        }
        val parserMs = (System.nanoTime() - parserStartedAt) / 1_000_000

        assertEquals(20, messages.count { OpenCodeMarkdownDetector.shouldRender(it) })
        println(
            "MARKDOWN_BENCHMARK kmp-joni parser-ms=$parserMs " +
                "grammar-ms=${metrics.totalMilliseconds(TextMateMetricStage.GrammarLoad)} " +
                "regex-compile-ms=${metrics.totalMilliseconds(TextMateMetricStage.RegexCompile)} " +
                "regex-search-ms=${metrics.totalMilliseconds(TextMateMetricStage.RegexSearch)} " +
                "regex-search-max-ms=${metrics.maximumMilliseconds(TextMateMetricStage.RegexSearch)} " +
                "native-regex-search-max-ms=${metrics.maximumMilliseconds(TextMateMetricStage.NativeRegexSearch)} " +
                "tokenize-ms=${metrics.totalMilliseconds(TextMateMetricStage.Tokenize)} " +
                "repair-ms=${metrics.totalMilliseconds(TextMateMetricStage.Repair)} " +
                "messages=20 code-lines=3000",
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
