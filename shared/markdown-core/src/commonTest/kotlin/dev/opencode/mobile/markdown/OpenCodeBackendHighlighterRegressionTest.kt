package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertTrue

class OpenCodeBackendHighlighterRegressionTest {
    @Test
    fun kotlinRegexBackendCoversWithCacheIdentifiersUniformly() {
        val code = withCacheSnippet()
        val result = highlighter(KotlinRegexTextMateRegexBackend).highlight("javascript", code)
        HighlighterRegressionAssertions.assertIdentifiersUniformlyCovered(
            code = code,
            spans = result.spans,
            identifiers = listOf("withCache", "client", "options", "maxSize", "Map"),
            label = "kotlin-regex",
        )
    }

    @Test
    fun iosHybridBackendCoversWithCacheIdentifiersUniformly() {
        val code = withCacheSnippet()
        val result = highlighter(OpenCodeOnigurumaRegexBackend).highlight("javascript", code)
        HighlighterRegressionAssertions.assertIdentifiersUniformlyCovered(
            code = code,
            spans = result.spans,
            identifiers = listOf("withCache", "client", "options", "maxSize", "Map"),
            label = "ios-hybrid",
        )
    }

    @Test
    fun iosHybridBackendNormalizesHttpClientPrivateFields() {
        val fixture =
            HighlighterGoldenFixtures.all.single { it.id == "javascript-http-client-class-repro" }
        val result = highlighter(OpenCodeOnigurumaRegexBackend).highlight(fixture.language, fixture.code)
        val privateFields = listOf("#baseUrl", "#headers", "#interceptors", "#timeout")
        HighlighterRegressionAssertions.assertPrivateFieldsMatchWebRoles(
            code = fixture.code,
            spans = result.spans,
            privateFields = privateFields,
            label = "ios-hybrid",
        )
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoOverlappingSpanRanges(fixture.id, result.spans)
    }

    @Test
    fun iosHybridBackendEmitsBackendFallbackDiagnostic() {
        val result = highlighter(OpenCodeOnigurumaRegexBackend).highlight("javascript", "const answer = 1")
        assertTrue(
            result.diagnostics.any { it.kind == MarkdownDiagnosticKind.HighlighterBackendFallback },
            "Expected ios hybrid backend fallback diagnostic, got ${result.diagnostics}",
        )
    }

    @Test
    fun iosHybridBackendCoversJavaMainClassRegressionFixture() {
        val fixture = HighlighterGoldenFixtures.all.single { it.id == "java-basic" }
        val result = highlighter(OpenCodeOnigurumaRegexBackend).highlight(fixture.language, fixture.code)
        HighlighterRegressionAssertions.assertTokenRoles(
            code = fixture.code,
            spans = result.spans,
            expectations =
                listOf(
                    "public" to CodeTokenRole.Keyword,
                    "Main" to CodeTokenRole.Type,
                    "println" to CodeTokenRole.Function,
                    "1" to CodeTokenRole.Number,
                ),
            label = "ios-hybrid-java-basic",
        )
        assertTrue(
            result.spans.any { span ->
                span.role == CodeTokenRole.String &&
                    fixture.code.substring(span.start, span.end).contains("Division by zero")
            },
            "Expected catch string literal in ${result.spans}",
        )
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoOverlappingSpanRanges(fixture.id, result.spans)
    }

    private fun withCacheSnippet(): String =
        """
        // Cache decorator for GET requests
        function withCache(client, options = {
          const { ttl = 60000, maxSize = 100 }
          const cache = new Map();
        """.trimIndent()

    private fun highlighter(backend: OpenCodeTextMateRegexBackend): OpenCodeSyntaxHighlighter =
        OpenCodeSyntaxHighlighter(
            grammarBundle = bundledGrammarBundle(),
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(backend),
        )

    private fun bundledGrammarBundle(): TextMateGrammarBundle =
        OpenCodeTextMateGrammarBundleLoader
            .loadBundled(
                OpenCodeTextMateResourceReader { resourcePath ->
                    OpenCodeBackendHighlighterRegressionTest::class.java.classLoader
                        ?.getResource(resourcePath)
                        ?.readText()
                },
            ).bundle ?: error("missing bundled TextMate grammar bundle")
}
