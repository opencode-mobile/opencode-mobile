package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertTrue

class WithCacheHighlighterDiagnosticTest {
    @Test
    fun withCacheSnippetIdentifiersAreUniformlyCoveredBySingleRole() {
        val code =
            """
            // Cache decorator for GET requests
            function withCache(client, options = {
              const { ttl = 60000, maxSize = 100 }
              const cache = new Map();
            """.trimIndent()

        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundledGrammarBundle(),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend),
            )

        val result = highlighter.highlight("javascript", code)
        val identifiers = listOf("withCache", "client", "options", "maxSize", "Map")

        identifiers.forEach { identifier ->
            val start = code.indexOf(identifier)
            assertTrue(start >= 0, "missing identifier $identifier")
            val end = start + identifier.length
            val covering =
                result.spans.filter { span -> span.end > start && span.start < end }
            val roles =
                (start until end).mapNotNull { index ->
                    covering
                        .filter { span -> span.start <= index && span.end > index }
                        .maxByOrNull { span -> span.end - span.start }
                        ?.role
                }
            val uncovered =
                (start until end).filter { index ->
                    covering.none { span -> span.start <= index && span.end > index }
                }
            assertTrue(
                uncovered.isEmpty(),
                "$identifier at $start..$end has uncovered indices $uncovered in spans $covering",
            )
            assertTrue(
                roles.distinct().size <= 1,
                "$identifier at $start..$end has fragmented roles $roles in spans $covering",
            )
        }
    }

    private fun bundledGrammarBundle(): TextMateGrammarBundle = testJavascriptGrammarBundle()
}
