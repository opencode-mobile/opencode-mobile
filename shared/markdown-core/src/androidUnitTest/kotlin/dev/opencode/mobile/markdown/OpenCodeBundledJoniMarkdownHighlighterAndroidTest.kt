package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertTrue

class OpenCodeBundledJoniMarkdownHighlighterAndroidTest {
    @Test
    fun bundledJoniHighlighterUsesTextMateNotSemanticFallback() {
        val fixture =
            HighlighterGoldenFixtures.all.single { it.id == "javascript-http-client-class-repro" }
        val highlighter =
            OpenCodeBundledJoniMarkdownHighlighter.create(
                OpenCodeTextMateResourceReader { resourcePath ->
                    javaClass.classLoader?.getResource(resourcePath)?.readText()
                },
            )
        val result = highlighter.highlight(fixture.language, fixture.code)

        assertTrue(
            result.diagnostics.none { it.kind == MarkdownDiagnosticKind.HighlighterFallback },
            "Bundled Joni highlighter should not use semantic fallback, got ${result.diagnostics}",
        )
        assertTrue(
            result.diagnostics.none { it.kind == MarkdownDiagnosticKind.HighlighterBackendFallback },
            "Bundled Joni highlighter should not report backend fallback, got ${result.diagnostics}",
        )
        HighlighterRegressionAssertions.assertPrivateFieldsUseVariableRole(
            code = fixture.code,
            spans = result.spans,
            privateFields = listOf("#baseUrl", "#headers", "#interceptors", "#timeout"),
            label = "android-joni",
        )
    }
}
