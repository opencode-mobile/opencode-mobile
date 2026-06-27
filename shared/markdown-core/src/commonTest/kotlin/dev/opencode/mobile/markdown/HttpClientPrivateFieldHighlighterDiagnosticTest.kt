package dev.opencode.mobile.markdown

import kotlin.test.Test

class HttpClientPrivateFieldHighlighterDiagnosticTest {
    @Test
    fun privateFieldDeclarationsAndMemberAccessMatchWebRoles() {
        val fixture =
            HighlighterGoldenFixtures.all.single { it.id == "javascript-http-client-class-repro" }
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend),
            )

        val result = highlighter.highlight(fixture.language, fixture.code)
        HighlighterRegressionAssertions.assertPrivateFieldsMatchWebRoles(
            code = fixture.code,
            spans = result.spans,
            privateFields = listOf("#baseUrl", "#headers", "#interceptors", "#timeout"),
            label = "kotlin-regex",
        )
    }
}
