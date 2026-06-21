package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class OpenCodeJoniRegexBackendAndroidTest {
    @Test
    fun joniBackendReportsOnigurumaCompatibility() {
        assertEquals("joni-oniguruma", OpenCodeJoniRegexBackend.name)
        assertTrue(OpenCodeJoniRegexBackend.isOnigurumaCompatible)
    }

    @Test
    fun joniBackendSupportsTextMateBackslashGAnchor() {
        val code = "> quote"
        val match =
            OpenCodeJoniRegexBackend.find(
                pattern = "\\G> ?",
                input = code,
                startIndex = 0,
                endIndex = code.length,
            )

        assertEquals(TextMateRegexMatch(0, 2, listOf(TextMateRegexCapture(0, 0, 2))), match)
    }

    @Test
    fun joniBackendSupportsNamedCapturesAndUtf8Offsets() {
        val code = "é<tag>body</tag>"
        val match =
            OpenCodeJoniRegexBackend.find(
                pattern = "<(?<name>[A-Za-z]+)>",
                input = code,
                startIndex = 0,
                endIndex = code.length,
            )

        assertEquals(1, match?.start)
        assertEquals(6, match?.end)
        assertEquals(TextMateRegexCapture(1, 2, 5), match?.captures?.firstOrNull { capture -> capture.index == 1 })
    }

    @Test
    fun joniTokenizerDoesNotEmitBackendFallbackDiagnostic() {
        val grammar =
            OpenCodeTextMateGrammarBundleLoader
                .load(
                    manifest =
                        TextMateGrammarManifest(
                            schemaVersion = 1,
                            generator = "test",
                            shikiVersion = "4.2.0",
                            languages =
                                listOf(
                                    TextMateGrammarManifestEntry(
                                        language = "sample",
                                        displayName = "Sample",
                                        aliases = emptyList(),
                                        grammarModule = "test",
                                        grammars =
                                            listOf(
                                                TextMateGrammarDescriptor(
                                                    name = "sample",
                                                    displayName = "Sample",
                                                    scopeName = "source.sample",
                                                    resourcePath = "textmate-grammars/grammars/sample.json",
                                                    embeddedLanguages = emptyList(),
                                                    aliases = emptyList(),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    reader =
                        OpenCodeTextMateResourceReader { resourcePath ->
                            when (resourcePath) {
                                "textmate-grammars/grammars/sample.json" ->
                                    """
                                    {
                                      "name": "sample",
                                      "scopeName": "source.sample",
                                      "patterns": [
                                        {
                                          "match": "\\bval\\b",
                                          "name": "keyword.control.sample"
                                        }
                                      ]
                                    }
                                    """.trimIndent()
                                else -> null
                            }
                        },
                ).primaryGrammarFor("sample") ?: error("missing sample grammar")

        val result = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend).tokenize(grammar, "val answer = 1")

        assertTrue(result.diagnostics.none { diagnostic -> diagnostic.kind == MarkdownDiagnosticKind.HighlighterBackendFallback })
        assertTrue(result.tokens.any { token -> token.scopes.any { scope -> scope.startsWith("keyword") } })
    }

    @Test
    fun joniHighlighterUsesBundledDiffGrammarForInsertedAndDeletedContent() {
        val highlighter = bundledJoniHighlighter()
        val code = "@@ -1,3 +1,3 @@\n-old line\n+new line\n context\n"

        val result = highlighter.highlight("diff", code)

        assertTrue(result.diagnostics.isEmpty(), "Expected no backend fallback diagnostics in ${result.diagnostics}")
        assertToken(code, result.spans, "@@", CodeTokenRole.Punctuation)
        assertToken(code, result.spans, "-", CodeTokenRole.Punctuation)
        assertToken(code, result.spans, "old line", CodeTokenRole.Deleted)
        assertToken(code, result.spans, "+", CodeTokenRole.Punctuation)
        assertToken(code, result.spans, "new line", CodeTokenRole.Inserted)
        assertFalse(result.spans.any { span -> span.role == CodeTokenRole.Deleted && code.substring(span.start, span.end) == "-" })
        assertFalse(result.spans.any { span -> span.role == CodeTokenRole.Inserted && code.substring(span.start, span.end) == "+" })
    }

    @Test
    fun joniHighlighterMatchesShikiDiffGoldenFixture() {
        assertGoldenFixtureParity("diff")
    }

    @Test
    fun joniHighlighterMatchesShikiJavascriptGoldenFixture() {
        assertGoldenFixtureParity("javascript")
    }

    @Test
    fun dumpJavascriptTextMateScopes() {
        val fixture = HighlighterGoldenFixtures.all.single { fixture -> fixture.language == "javascript" }
        val bundle =
            OpenCodeTextMateGrammarBundleLoader
                .loadBundled(
                    OpenCodeTextMateResourceReader { resourcePath ->
                        javaClass.classLoader?.getResource(resourcePath)?.readText()
                    },
                ).bundle ?: error("missing bundled TextMate grammar bundle")
        val grammar = bundle.primaryGrammarFor("javascript") ?: error("missing javascript grammar")
        val result = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend).tokenize(grammar, fixture.code, bundle)

        result.tokens.forEach { token ->
            println("${token.start}-${token.end} '${fixture.code.substring(token.start, token.end)}' ${token.scopes}")
        }
    }

    @Test
    fun joniHighlighterMatchesShikiJsonGoldenFixture() {
        assertGoldenFixtureParity("json")
    }

    @Test
    fun joniHighlighterMatchesShikiYamlGoldenFixture() {
        assertGoldenFixtureParity("yaml")
    }

    @Test
    fun joniHighlighterMatchesShikiCssGoldenFixture() {
        assertGoldenFixtureParity("css")
    }

    @Test
    fun joniHighlighterMatchesShikiHtmlGoldenFixture() {
        assertGoldenFixtureParity("html")
    }

    private fun bundledJoniHighlighter(): OpenCodeSyntaxHighlighter {
        val bundle =
            OpenCodeTextMateGrammarBundleLoader
                .loadBundled(
                    OpenCodeTextMateResourceReader { resourcePath ->
                        javaClass.classLoader?.getResource(resourcePath)?.readText()
                    },
                ).bundle ?: error("missing bundled TextMate grammar bundle")

        return OpenCodeSyntaxHighlighter(
            grammarBundle = bundle,
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend),
        )
    }

    private fun assertGoldenFixtureParity(language: String) {
        val fixture = HighlighterGoldenFixtures.all.single { fixture -> fixture.language == language }
        val result = bundledJoniHighlighter().highlight(fixture.language, fixture.code)

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

    private fun assertToken(
        code: String,
        spans: List<CodeSpan>,
        text: String,
        role: CodeTokenRole,
    ) {
        assertTrue(
            spans.any { span ->
                span.role == role && code.substring(span.start, span.end) == text
            },
            "Expected $role token for '$text' in $spans",
        )
    }
}
