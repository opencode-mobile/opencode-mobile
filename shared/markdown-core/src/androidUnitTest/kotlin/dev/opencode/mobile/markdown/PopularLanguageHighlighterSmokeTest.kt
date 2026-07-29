package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopularLanguageHighlighterSmokeTest {
    @Test
    fun bundledTextMateHighlighterSupportsPopularLanguagePack() {
        val highlighter = bundledJoniHighlighter()
        popularLanguageFixtureIds.forEach { fixtureId ->
            val fixture = HighlighterGoldenFixtures.all.single { it.id == fixtureId }
            assertTrue(
                highlighter.supportsLanguage(fixture.language),
                "Expected bundled grammars to support ${fixture.language}",
            )
            fixture.aliases.forEach { alias ->
                assertTrue(
                    highlighter.supportsLanguage(alias),
                    "Expected bundled grammars to support alias $alias for ${fixture.language}",
                )
            }
        }
    }

    @Test
    fun bundledTextMateHighlighterEmitsSpansForPopularLanguagePack() {
        val highlighter = bundledJoniHighlighter()
        popularLanguageFixtureIds.forEach { fixtureId ->
            val fixture = HighlighterGoldenFixtures.all.single { it.id == fixtureId }
            val result = highlighter.highlight(fixture.language, fixture.code)
            assertTrue(
                result.spans.isNotEmpty(),
                "Expected ${fixture.language} to emit syntax spans",
            )
            assertFalse(
                result.diagnostics.any { it.kind == MarkdownDiagnosticKind.HighlighterFallback },
                "Expected ${fixture.language} to use TextMate, not semantic fallback: ${result.diagnostics}",
            )
        }
    }

    private fun bundledJoniHighlighter(): OpenCodeSyntaxHighlighter =
        OpenCodeSyntaxHighlighter(
            grammarBundle = bundledGrammarBundle(),
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexRuntime()),
        )

    private companion object {
        val popularLanguageFixtureIds =
            listOf(
                "java-basic",
                "csharp-basic",
                "c-basic",
                "cpp-basic",
                "ruby-basic",
                "php-basic",
                "sql-basic",
                "toml-basic",
                "xml-basic",
                "scala-basic",
                "lua-basic",
                "docker-basic",
                "vue-basic",
                "svelte-basic",
                "graphql-basic",
                "powershell-basic",
                "perl-basic",
                "haskell-basic",
                "elixir-basic",
                "erlang-basic",
                "r-basic",
                "zig-basic",
                "make-basic",
            )
    }
}
