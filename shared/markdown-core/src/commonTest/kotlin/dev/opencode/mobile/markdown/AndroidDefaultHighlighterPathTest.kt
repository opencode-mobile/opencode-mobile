package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidDefaultHighlighterPathTest {
    @Test
    fun androidDefaultHighlighterUsesSemanticFallbackNotTextMate() {
        val fixture =
            HighlighterGoldenFixtures.all.single { it.id == "javascript-http-client-class-repro" }
        val result = OpenCodeSyntaxHighlighter.default.highlight(fixture.language, fixture.code)

        assertTrue(
            result.diagnostics.any { it.kind == MarkdownDiagnosticKind.HighlighterFallback },
            "Android default highlighter should report fallback diagnostics, got ${result.diagnostics}",
        )
        assertTrue(
            result.spans.none { span ->
                span.start <= fixture.code.indexOf("#timeout") &&
                    span.end >= fixture.code.indexOf("#timeout") + "#timeout".length &&
                    span.role == CodeTokenRole.Type
            },
            "Fallback highlighter should not emit TextMate-style Type spans for private fields",
        )
    }
}
