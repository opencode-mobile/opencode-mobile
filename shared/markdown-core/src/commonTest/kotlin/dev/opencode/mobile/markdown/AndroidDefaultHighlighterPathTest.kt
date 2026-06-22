package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidDefaultHighlighterPathTest {
    @Test
    fun syntaxHighlighterCompanionDefaultRemainsSemanticFallback() {
        val fixture =
            HighlighterGoldenFixtures.all.single { it.id == "javascript-http-client-class-repro" }
        val result = OpenCodeSyntaxHighlighter.default.highlight(fixture.language, fixture.code)

        assertTrue(
            result.diagnostics.any { it.kind == MarkdownDiagnosticKind.HighlighterFallback },
            "OpenCodeSyntaxHighlighter.default should remain the semantic fallback scaffold, got ${result.diagnostics}",
        )
    }
}
