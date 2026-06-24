package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenCodeMarkdownHighlighterSpanInvariantTest {
    @Test
    fun highlightedJavascriptRegressionFixturesHaveNoDuplicateSpanRanges() {
        val fixtureIds = javascriptRegressionFixtureIds()
        val highlighter = bundledKotlinRegexHighlighter()

        fixtureIds.forEach { fixtureId ->
            val fixture = HighlighterGoldenFixtures.all.single { it.id == fixtureId }
            val result = highlighter.highlight(fixture.language, fixture.code)
            assertNoDuplicateSpanRanges(fixtureId, result.spans)
            assertNoOverlappingSpanRanges(fixtureId, result.spans)
        }
    }

    private fun javascriptRegressionFixtureIds(): List<String> =
        listOf(
            "javascript-basic",
            "javascript-request-repro",
            "javascript-http-error-repro",
            "javascript-private-field-template",
            "javascript-event-emitter-repro",
            "javascript-http-client-class-repro",
            "javascript-patch-path-repro",
        )

    private fun bundledKotlinRegexHighlighter(): OpenCodeSyntaxHighlighter {
        val bundle = testJavascriptGrammarBundle()

        return OpenCodeSyntaxHighlighter(
            grammarBundle = bundle,
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend),
        )
    }

    companion object {
        fun assertNoDuplicateSpanRanges(
            fixtureId: String,
            spans: List<CodeSpan>,
        ) {
            val duplicateRanges =
                spans
                    .map { span -> span.start to span.end }
                    .groupingBy { range -> range }
                    .eachCount()
                    .filterValues { count -> count > 1 }
            assertTrue(
                duplicateRanges.isEmpty(),
                "$fixtureId has duplicate span ranges: $duplicateRanges in $spans",
            )
        }

        fun assertNoOverlappingSpanRanges(
            fixtureId: String,
            spans: List<CodeSpan>,
        ) {
            spans
                .sortedBy { it.start }
                .zipWithNext { left, right ->
                    assertTrue(
                        left.end <= right.start,
                        "$fixtureId has overlapping spans: $left and $right in $spans",
                    )
                }
        }
    }
}
