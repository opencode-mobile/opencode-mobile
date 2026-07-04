package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

class OpenCodeLibOnigIosTest {
    @Test
    fun libOnigBackendReportsOnigurumaCompatibility() {
        assertEquals("libonig", OpenCodeLibOnigRegexBackend.name)
        assertTrue(OpenCodeLibOnigRegexBackend.isOnigurumaCompatible)
    }

    @Test
    fun libOnigBackendSupportsTextMateBackslashGAnchor() {
        val code = "> quote"
        val match =
            OpenCodeLibOnigRegexBackend.find(
                pattern = "\\G> ?",
                input = code,
                startIndex = 0,
                endIndex = code.length,
            )

        assertEquals(TextMateRegexMatch(0, 2, listOf(TextMateRegexCapture(0, 0, 2))), match)
    }

    @Test
    fun libOnigBackendSupportsNamedCapturesAndUtf8Offsets() {
        val code = "é<tag>body</tag>"
        val match =
            OpenCodeLibOnigRegexBackend.find(
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
    fun libOnigTokenizerDoesNotEmitBackendFallbackDiagnostic() {
        val grammar = sampleGrammar()
        val result = OpenCodeTextMateNativeTokenizer(OpenCodeLibOnigRegexBackend).tokenize(grammar, "val answer = 1")

        assertTrue(result.diagnostics.none { diagnostic -> diagnostic.kind == MarkdownDiagnosticKind.HighlighterBackendFallback })
        assertTrue(result.tokens.any { token -> token.scopes.any { scope -> scope.startsWith("keyword") } })
    }

    @Test
    fun libOnigMatchesZeroWidthClassEndAfterClosingBrace() {
        val code = "class Foo {}"
        val match =
            OpenCodeLibOnigRegexBackend.find(
                pattern = "(?<=})",
                input = code,
                startIndex = 0,
                endIndex = code.length,
            )

        assertEquals(12, match?.start)
        assertEquals(12, match?.end)
    }

    @Test
    fun libOnigBackendCachesUncompilablePatterns() {
        val badPattern = "[unclosed"

        assertEquals(
            null,
            OpenCodeLibOnigRegexBackend.find(
                pattern = badPattern,
                input = "test",
                startIndex = 0,
                endIndex = 4,
            ),
        )
        assertEquals(
            null,
            OpenCodeLibOnigRegexBackend.find(
                pattern = badPattern,
                input = "test",
                startIndex = 0,
                endIndex = 4,
            ),
        )
    }

    @Test
    fun libOnigBackendCoversWithCacheIdentifiersUniformly() {
        val code =
            """
            // Cache decorator for GET requests
            function withCache(client, options = {
              const { ttl = 60000, maxSize = 100 }
              const cache = new Map();
            """.trimIndent()
        val result = bundledHighlighter().highlight("javascript", code)
        HighlighterRegressionAssertions.assertIdentifiersUniformlyCovered(
            code = code,
            spans = result.spans,
            identifiers = listOf("withCache", "client", "options", "maxSize", "Map"),
            label = "libonig",
        )
        assertTrue(result.diagnostics.isEmpty(), "Expected no backend fallback diagnostics in ${result.diagnostics}")
    }

    @Test
    fun libOnigBackendNormalizesHttpClientPrivateFields() {
        val fixture =
            HighlighterGoldenFixtures.all.single { it.id == "javascript-http-client-class-repro" }
        val result = bundledHighlighter().highlight(fixture.language, fixture.code)
        HighlighterRegressionAssertions.assertPrivateFieldsMatchWebRoles(
            code = fixture.code,
            spans = result.spans,
            privateFields = listOf("#baseUrl", "#headers", "#interceptors", "#timeout"),
            label = "libonig",
        )
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoOverlappingSpanRanges(fixture.id, result.spans)
        assertTrue(result.diagnostics.isEmpty(), "Expected no backend fallback diagnostics in ${result.diagnostics}")
    }

    @Test
    fun libOnigMatchesShikiDiffGoldenFixture() {
        val fixture = HighlighterGoldenFixtures.all.single { it.id == "diff-basic" }
        val result = bundledHighlighter().highlight(fixture.language, fixture.code)

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

    @Test
    fun libOnigMatchesShikiJavascriptGoldenFixture() {
        Top10GoldenFixtureAssertions.assertExactGoldenFixtureParity(bundledHighlighter(), "javascript-basic")
    }

    @Test
    fun libOnigMatchesShikiTypescriptGoldenFixture() {
        Top10GoldenFixtureAssertions.assertExactGoldenFixtureParity(bundledHighlighter(), "typescript-basic")
    }

    @Test
    fun libOnigMatchesShikiPythonGoldenFixture() {
        Top10GoldenFixtureAssertions.assertExactGoldenFixtureParity(bundledHighlighter(), "python-basic")
    }

    @Test
    fun libOnigMatchesShikiKotlinGoldenFixture() {
        Top10GoldenFixtureAssertions.assertExactGoldenFixtureParity(bundledHighlighter(), "kotlin-basic")
    }

    @Test
    fun libOnigMatchesShikiSwiftGoldenFixture() {
        Top10GoldenFixtureAssertions.assertExactGoldenFixtureParity(bundledHighlighter(), "swift-basic")
    }

    @Test
    fun libOnigMatchesShikiShellscriptGoldenFixture() {
        Top10GoldenFixtureAssertions.assertExactGoldenFixtureParity(bundledHighlighter(), "shellscript-basic")
    }

    @Test
    fun libOnigMatchesShikiRustGoldenFixture() {
        Top10GoldenFixtureAssertions.assertExactGoldenFixtureParity(bundledHighlighter(), "rust-basic")
    }

    @Test
    fun libOnigMatchesShikiGoGoldenFixture() {
        Top10GoldenFixtureAssertions.assertExactGoldenFixtureParity(bundledHighlighter(), "go-basic")
    }

    @Test
    fun libOnigMatchesShikiJsonGoldenFixture() {
        Top10GoldenFixtureAssertions.assertExactGoldenFixtureParity(bundledHighlighter(), "json-basic")
    }

    @Test
    fun libOnigMatchesShikiYamlGoldenFixture() {
        Top10GoldenFixtureAssertions.assertExactGoldenFixtureParity(bundledHighlighter(), "yaml-basic")
    }

    @Test
    fun libOnigJavascriptRegressionFixturesLeaveNoOpenRulesAtEof() {
        val fixtureIds =
            listOf(
                "javascript-patch-path-repro",
                "javascript-http-client-class-repro",
                "javascript-event-emitter-repro",
            )
        val bundle = bundledGrammarBundle()
        val grammar = bundle.primaryGrammarFor("javascript") ?: error("missing javascript grammar")
        val tokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeLibOnigRegexBackend)

        fixtureIds.forEach { fixtureId ->
            val fixture = HighlighterGoldenFixtures.all.single { it.id == fixtureId }
            var state = TextMateLineState.Initial
            fixture.code.split("\n").forEach { line ->
                state = tokenizer.tokenizeLine(grammar, line, bundle, initialState = state).state
            }
            assertTrue(
                state.openRules.isEmpty(),
                "$fixtureId leaked open rules: ${state.openRules.map { it.pattern.name ?: it.endPattern }}",
            )
        }
    }

    @Test
    fun benchmarkLongMarkdownHistoryLibOnigBackend() {
        val highlighter = bundledHighlighter()
        val messages = longMarkdownHistory()
        val markdownCount = messages.count { OpenCodeMarkdownDetector.shouldRender(it) }

        val parserMs =
            measureTime {
                messages.forEach { message ->
                    if (OpenCodeMarkdownDetector.shouldRender(message)) {
                        OpenCodeMarkdownParser.parse(message, highlighter)
                    }
                }
            }.inWholeMilliseconds

        assertEquals(20, markdownCount)
        assertFalse(parserMs < 0)
        println(
            "MARKDOWN_BENCHMARK kmp-libonig parser-ms=$parserMs messages=20 code-lines=3000",
        )
    }

    private fun bundledHighlighter(): OpenCodeSyntaxHighlighter =
        OpenCodeSyntaxHighlighter(
            grammarBundle = bundledGrammarBundle(),
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeLibOnigRegexBackend),
        )

    private fun sampleGrammar(): TextMateGrammarPayload {
        val bundle =
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
        return bundle
    }

    private fun longMarkdownHistory(): List<String> =
        (1..20).map { messageIndex ->
            buildString {
                append("## Response $messageIndex\n\n```typescript\n")
                repeat(150) { lineIndex ->
                    append("const item$lineIndex = response$lineIndex?.value ?? 'markdown benchmark';\n")
                }
                append("```\n")
            }
        }
}
