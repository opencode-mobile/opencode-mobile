package dev.opencode.mobile.markdown

import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal object Top10GoldenFixtureAssertions {
    val top10BasicFixtureIds =
        listOf(
            "javascript-basic",
            "typescript-basic",
            "python-basic",
            "kotlin-basic",
            "swift-basic",
            "json-basic",
            "yaml-basic",
            "shellscript-basic",
            "rust-basic",
            "go-basic",
        )

    fun assertExactGoldenFixtureParity(
        highlighter: OpenCodeCodeHighlighter,
        fixtureId: String,
    ) {
        val fixture = HighlighterGoldenFixtures.all.single { it.id == fixtureId }
        val result = highlighter.highlight(fixture.language, fixture.code)

        assertTrue(result.diagnostics.isEmpty(), "Expected no backend fallback diagnostics in ${result.diagnostics}")
        assertEquals(
            fixture.tokens,
            result.spans.map { span ->
                HighlighterGoldenToken(
                    start = span.start,
                    end = span.end,
                    role = span.role,
                    text = fixture.code.substring(span.start, span.end),
                )
            },
        )
    }

    fun bundledHighlighter(regexBackend: OpenCodeTextMateRegexBackend): OpenCodeSyntaxHighlighter =
        OpenCodeSyntaxHighlighter(
            grammarBundle = bundledGrammarBundle(),
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(regexBackend),
        )
}
