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
        assertTokenAt(code, result.spans, "-", CodeTokenRole.Punctuation, occurrence = 1)
        assertToken(code, result.spans, "old line", CodeTokenRole.Deleted)
        assertTokenAt(code, result.spans, "+", CodeTokenRole.Punctuation, occurrence = 1)
        assertToken(code, result.spans, "new line", CodeTokenRole.Inserted)
        assertFalse(result.spans.any { span -> span.role == CodeTokenRole.Deleted && code.substring(span.start, span.end) == "-" })
        assertFalse(result.spans.any { span -> span.role == CodeTokenRole.Inserted && code.substring(span.start, span.end) == "+" })
    }

    @Test
    fun joniHighlighterMatchesShikiDiffGoldenFixture() {
        assertGoldenFixtureParity("diff-basic")
    }

    @Test
    fun joniHighlighterMatchesShikiJavascriptGoldenFixture() {
        assertRegressionFixtureCoverage("javascript-basic")
    }

    @Test
    fun joniHighlighterMatchesShikiJavascriptRequestReproFixture() {
        assertRegressionFixtureCoverage("javascript-request-repro")
        assertNoLeakedTemplateRules("javascript-request-repro")
    }

    @Test
    fun joniHighlighterMatchesShikiJavascriptHttpErrorReproFixture() {
        assertRegressionFixtureCoverage("javascript-http-error-repro")
        assertNoLeakedClassRulesAfterHttpError()
    }

    @Test
    fun joniHighlighterMatchesShikiJavascriptPrivateFieldTemplateFixture() {
        assertRegressionFixtureCoverage("javascript-private-field-template")
        assertNoLeakedTemplateRules("javascript-private-field-template")
    }

    @Test
    fun javascriptSimpleTemplateCloses() {
        assertTemplateClosesOnSameLine("    const x = `hello`;")
    }

    @Test
    fun javascriptSubstitutionTemplateCloses() {
        assertTemplateClosesOnSameLine("    const x = `${'$'}{value}`;")
    }

    @Test
    fun javascriptPrivateFieldSubstitutionTemplateCloses() {
        assertTemplateClosesOnSameLine("    const x = `${'$'}{this.#baseUrl}`;")
    }

    @Test
    fun javascriptDualSubstitutionTemplateCloses() {
        assertTemplateClosesOnSameLine("    const x = `${'$'}{this.#baseUrl}${'$'}{path}`;")
    }

    private fun assertTemplateClosesOnSameLine(line: String) {
        val bundle =
            OpenCodeTextMateGrammarBundleLoader
                .loadBundled(
                    OpenCodeTextMateResourceReader { resourcePath ->
                        javaClass.classLoader?.getResource(resourcePath)?.readText()
                    },
                ).bundle ?: error("missing bundled TextMate grammar bundle")
        val grammar = bundle.primaryGrammarFor("javascript") ?: error("missing javascript grammar")
        val result = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend).tokenizeLine(grammar, line, bundle)
        val templateOpenRules = result.state.openRules.filter { it.endPattern == "`" }
        assertTrue(
            templateOpenRules.isEmpty(),
            "Line '$line' left template open: ${result.state.openRules.map { it.endPattern }}",
        )
    }

    @Test
    fun joniMatchesZeroWidthClassEndAfterClosingBrace() {
        val code = "class Foo {}"
        val match =
            OpenCodeJoniRegexBackend.find(
                pattern = "(?<=})",
                input = code,
                startIndex = 0,
                endIndex = code.length,
            )

        assertEquals(12, match?.start)
        assertEquals(12, match?.end)
    }

    @Test
    fun javascriptClassDeclarationClosesWithoutLeakedState() {
        val bundle = bundledGrammarBundle()
        val grammar = javascriptGrammar(bundle)
        val tokenizer = bundledTokenizer()
        val code = "class HttpError extends Error {\n  constructor() {}\n}\n"
        var state = TextMateLineState.Initial
        code.split("\n").forEach { line ->
            state = tokenizer.tokenizeLine(grammar, line, bundle, initialState = state).state
        }
        val leakedClassRules = state.openRules.filter { it.pattern.name?.contains("class") == true }
        assertTrue(
            leakedClassRules.isEmpty(),
            "Leaked class rules: ${leakedClassRules.map { it.pattern.name }}",
        )
    }

    @Test
    fun javascriptTemplateLineClosesOnSameLine() {
        val bundle =
            OpenCodeTextMateGrammarBundleLoader
                .loadBundled(
                    OpenCodeTextMateResourceReader { resourcePath ->
                        javaClass.classLoader?.getResource(resourcePath)?.readText()
                    },
                ).bundle ?: error("missing bundled TextMate grammar bundle")
        val grammar = bundle.primaryGrammarFor("javascript") ?: error("missing javascript grammar")
        val tokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend)
        val prefix =
            """
              async request(method, path, options = {}) {
                let config = {
            """.trimIndent()
        val templateLine = "      url: `${'$'}{this.#baseUrl}${'$'}{path}`,"
        var state = TextMateLineState.Initial
        prefix.split("\n").forEach { line ->
            state = tokenizer.tokenizeLine(grammar, line, bundle, initialState = state).state
        }
        state = tokenizer.tokenizeLine(grammar, templateLine, bundle, initialState = state).state
        val templateOpenRules = state.openRules.filter { it.endPattern == "`" }
        assertTrue(
            templateOpenRules.isEmpty(),
            "Template literal should close on same line; open template rules: ${templateOpenRules.size}, all ends: ${state.openRules.map { it.endPattern }}",
        )
    }

    private fun bundledTokenizer(): OpenCodeTextMateNativeTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeJoniRegexBackend)

    private fun bundledGrammarBundle(): TextMateGrammarBundle {
        return OpenCodeTextMateGrammarBundleLoader
            .loadBundled(
                OpenCodeTextMateResourceReader { resourcePath ->
                    javaClass.classLoader?.getResource(resourcePath)?.readText()
                },
            ).bundle ?: error("missing bundled TextMate grammar bundle")
    }

    private fun javascriptGrammar(bundle: TextMateGrammarBundle = bundledGrammarBundle()) =
        bundle.primaryGrammarFor("javascript") ?: error("missing javascript grammar")

    private fun assertNoLeakedTemplateRules(fixtureId: String) {
        val fixture = HighlighterGoldenFixtures.all.single { it.id == fixtureId }
        val bundle = bundledGrammarBundle()
        val grammar = javascriptGrammar(bundle)
        val tokenizer = bundledTokenizer()
        var state = TextMateLineState.Initial
        fixture.code.split("\n").forEach { line ->
            state = tokenizer.tokenizeLine(grammar, line, bundle, initialState = state).state
        }
        val leakedTemplates = state.openRules.filter { it.endPattern == "`" }
        assertTrue(
            leakedTemplates.isEmpty(),
            "Leaked template rules at EOF: ${leakedTemplates.map { it.endPattern }}",
        )
    }

    private fun assertNoLeakedClassRulesAfterHttpError() {
        val fixture = HighlighterGoldenFixtures.all.single { it.id == "javascript-http-error-repro" }
        val bundle = bundledGrammarBundle()
        val grammar = javascriptGrammar(bundle)
        val tokenizer = bundledTokenizer()
        var state = TextMateLineState.Initial
        fixture.code.split("\n").forEach { line ->
            state = tokenizer.tokenizeLine(grammar, line, bundle, initialState = state).state
        }
        val leakedClassRules = state.openRules.filter { it.pattern.name?.contains("class") == true }
        assertTrue(
            leakedClassRules.isEmpty(),
            "Leaked class rules after HttpError fixture: ${leakedClassRules.map { it.pattern.name }}",
        )
    }

    @Test
    fun joniHighlighterCoversJavascriptPatchPathRegressionFixture() {
        assertRegressionFixtureCoverage("javascript-patch-path-repro")
        assertNoDuplicateSpanRanges("javascript-patch-path-repro")
        assertNoOverlappingSpanRanges("javascript-patch-path-repro")
        assertNoLeakedOpenRulesAtEof("javascript-patch-path-repro")
    }

    @Test
    fun joniHighlighterCoversJavascriptHttpClientClassRegressionFixture() {
        assertRegressionFixtureCoverage("javascript-http-client-class-repro")
        assertNoDuplicateSpanRanges("javascript-http-client-class-repro")
        assertNoOverlappingSpanRanges("javascript-http-client-class-repro")
        assertNoLeakedOpenRulesAtEof("javascript-http-client-class-repro")
    }

    @Test
    fun joniHighlighterCoversJavascriptEventEmitterRegressionFixture() {
        assertRegressionFixtureCoverage("javascript-event-emitter-repro")
        assertNoDuplicateSpanRanges("javascript-event-emitter-repro")
        assertNoOverlappingSpanRanges("javascript-event-emitter-repro")
        assertNoLeakedOpenRulesAtEof("javascript-event-emitter-repro")
    }

    @Test
    fun joniHighlighterMatchesShikiJsonGoldenFixture() {
        assertGoldenFixtureParity("json-basic")
    }

    @Test
    fun joniHighlighterMatchesShikiYamlGoldenFixture() {
        assertGoldenFixtureParity("yaml-basic")
    }

    @Test
    fun joniHighlighterMatchesShikiCssGoldenFixture() {
        assertGoldenFixtureParity("css-basic")
    }

    @Test
    fun joniHighlighterMatchesShikiHtmlGoldenFixture() {
        assertGoldenFixtureParity("html-basic")
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

    private fun assertNoDuplicateSpanRanges(fixtureId: String) {
        val fixture = HighlighterGoldenFixtures.all.single { it.id == fixtureId }
        val result = bundledJoniHighlighter().highlight(fixture.language, fixture.code)
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoDuplicateSpanRanges(fixtureId, result.spans)
    }

    private fun assertNoOverlappingSpanRanges(fixtureId: String) {
        val fixture = HighlighterGoldenFixtures.all.single { it.id == fixtureId }
        val result = bundledJoniHighlighter().highlight(fixture.language, fixture.code)
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoOverlappingSpanRanges(fixtureId, result.spans)
    }

    private fun assertGoldenFixtureParity(fixtureId: String) {
        if (fixtureId.endsWith("-repro")) {
            assertRegressionFixtureCoverage(fixtureId)
            return
        }
        assertExactGoldenFixtureParity(fixtureId)
    }

    private fun assertExactGoldenFixtureParity(fixtureId: String) {
        val fixture = HighlighterGoldenFixtures.all.single { fixture -> fixture.id == fixtureId }
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

    private fun assertRegressionFixtureCoverage(fixtureId: String) {
        val fixture = HighlighterGoldenFixtures.all.single { fixture -> fixture.id == fixtureId }
        val result = bundledJoniHighlighter().highlight(fixture.language, fixture.code)

        assertTrue(result.diagnostics.isEmpty(), "Expected no backend fallback diagnostics in ${result.diagnostics}")
        when (fixtureId) {
            "javascript-request-repro" -> {
                assertToken(fixture.code, result.spans, "// Core request method", CodeTokenRole.Comment)
                assertTokenAfter(fixture.code, result.spans, "\n  ", "async", CodeTokenRole.Variable)
                assertTokenAfter(fixture.code, result.spans, "async ", "request", CodeTokenRole.Function)
                assertToken(fixture.code, result.spans, "#baseUrl", CodeTokenRole.Variable)
                assertToken(fixture.code, result.spans, "#headers", CodeTokenRole.Variable)
                assertTokenAfter(fixture.code, result.spans, "      config = ", "await", CodeTokenRole.Keyword)
            }
            "javascript-http-error-repro" -> {
                assertToken(fixture.code, result.spans, "// Custom error class", CodeTokenRole.Comment)
                assertTokenAfter(fixture.code, result.spans, "\n", "class", CodeTokenRole.Keyword)
                assertToken(fixture.code, result.spans, "HttpError", CodeTokenRole.Type)
                assertTokenAfter(fixture.code, result.spans, "}\n\n", "// Retry wrapper", CodeTokenRole.Comment)
                assertToken(fixture.code, result.spans, "withRetry", CodeTokenRole.Function)
                assertToken(fixture.code, result.spans, "maxRetries", CodeTokenRole.Variable)
            }
            "javascript-private-field-template" -> {
                assertToken(fixture.code, result.spans, "class", CodeTokenRole.Keyword)
                assertToken(fixture.code, result.spans, "Client", CodeTokenRole.Type)
                assertTokenAfter(fixture.code, result.spans, "this.", "#baseUrl", CodeTokenRole.Variable)
                assertTokenAfter(fixture.code, result.spans, "#baseUrl;\n  ", "greet", CodeTokenRole.Type)
            }
            "javascript-basic" -> {
                assertToken(fixture.code, result.spans, "const", CodeTokenRole.Keyword)
                assertToken(fixture.code, result.spans, "answer", CodeTokenRole.Variable)
                assertToken(fixture.code, result.spans, "42", CodeTokenRole.Number)
                assertTokenAfter(fixture.code, result.spans, "\n", "console", CodeTokenRole.Variable)
            }
            "javascript-event-emitter-repro" -> {
                assertToken(fixture.code, result.spans, "// EventEmitter - A simple pub/sub implementation", CodeTokenRole.Comment)
                assertTokenAfter(fixture.code, result.spans, "\n", "class", CodeTokenRole.Keyword)
                assertTokenAfter(fixture.code, result.spans, "class ", "EventEmitter", CodeTokenRole.Type)
                assertToken(fixture.code, result.spans, "Map", CodeTokenRole.Type)
                assertTokenAfter(fixture.code, result.spans, "  off(event, callback) {\n    if (!this.events.has(event)) return;\n    ", "const", CodeTokenRole.Keyword)
                assertToken(fixture.code, result.spans, "emit", CodeTokenRole.Function)
            }
            "javascript-http-client-class-repro" -> {
                assertToken(fixture.code, result.spans, "// HTTP Client with interceptors, retry logic", CodeTokenRole.Comment)
                assertTokenAfter(fixture.code, result.spans, "\n", "class", CodeTokenRole.Keyword)
                assertTokenAfter(fixture.code, result.spans, "class ", "HttpClient", CodeTokenRole.Type)
                assertTrue(
                    result.spans.any { span ->
                        span.start <= 68 && span.end >= 75 &&
                            span.role in setOf(CodeTokenRole.Type, CodeTokenRole.Property, CodeTokenRole.Variable)
                    },
                    "Expected contiguous private-field token for #baseUrl in ${result.spans}",
                )
                assertToken(fixture.code, result.spans, "Map", CodeTokenRole.Function)
                assertToken(fixture.code, result.spans, "push", CodeTokenRole.Function)
            }
            "javascript-patch-path-repro" -> {
                assertToken(fixture.code, result.spans, "patch", CodeTokenRole.Function)
                assertToken(fixture.code, result.spans, "path", CodeTokenRole.Variable)
                assertToken(fixture.code, result.spans, "return", CodeTokenRole.Keyword)
                assertToken(fixture.code, result.spans, "PATCH", CodeTokenRole.String)
            }
            else -> error("Unhandled regression fixture $fixtureId")
        }
    }

    private fun assertNoLeakedOpenRulesAtEof(fixtureId: String) {
        val fixture = HighlighterGoldenFixtures.all.single { it.id == fixtureId }
        val bundle = bundledGrammarBundle()
        val grammar = javascriptGrammar(bundle)
        val tokenizer = bundledTokenizer()
        var state = TextMateLineState.Initial
        fixture.code.split("\n").forEach { line ->
            state = tokenizer.tokenizeLine(grammar, line, bundle, initialState = state).state
        }
        assertTrue(
            state.openRules.isEmpty(),
            "Leaked open rules at EOF for $fixtureId: ${state.openRules.map { it.pattern.name ?: it.endPattern }}",
        )
    }

    private fun assertTokenAfter(
        code: String,
        spans: List<CodeSpan>,
        prefix: String,
        text: String,
        role: CodeTokenRole,
    ) {
        val prefixIndex = code.indexOf(prefix)
        assertTrue(prefixIndex >= 0, "Expected source to contain prefix '$prefix'")
        val start = prefixIndex + prefix.length
        assertTrue(
            code.regionMatches(start, text, 0, text.length),
            "Expected '$text' to follow '$prefix' at $start",
        )
        val end = start + text.length
        assertTrue(
            spansCoverRange(spans, start, end, role),
            "Expected $role token covering '$text' at $start in $spans",
        )
    }

    private fun assertTokenAt(
        code: String,
        spans: List<CodeSpan>,
        text: String,
        role: CodeTokenRole,
        occurrence: Int = 0,
    ) {
        var searchFrom = 0
        repeat(occurrence) {
            searchFrom = code.indexOf(text, searchFrom) + 1
        }
        val start = code.indexOf(text, searchFrom)
        assertTrue(start >= 0, "Expected source to contain '$text' occurrence $occurrence")
        val end = start + text.length
        assertTrue(
            spans.any { span -> span.start <= start && span.end >= end && span.role == role },
            "Expected $role token covering '$text' at $start in $spans",
        )
    }

    private fun assertToken(
        code: String,
        spans: List<CodeSpan>,
        text: String,
        role: CodeTokenRole,
        offset: Int = 0,
    ) {
        val start = code.indexOf(text, offset)
        assertTrue(start >= 0, "Expected source to contain '$text'")
        val end = start + text.length
        assertTrue(
            spansCoverRange(spans, start, end, role),
            "Expected $role token covering '$text' in $spans",
        )
    }

    private fun spansCoverRange(
        spans: List<CodeSpan>,
        start: Int,
        end: Int,
        role: CodeTokenRole,
    ): Boolean {
        val relevant =
            spans
                .filter { span -> span.role == role && span.end > start && span.start < end }
                .sortedBy { span -> span.start }
        var cursor = start
        for (span in relevant) {
            if (span.start > cursor) {
                return false
            }
            cursor = maxOf(cursor, span.end)
        }
        return cursor >= end
    }
}
