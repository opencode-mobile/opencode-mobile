package dev.opencode.mobile.markdown

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
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
    fun libOnigBackendAccountsForCachedAndDecompositionProbeAllocations() {
        OpenCodeLibOnigRegexBackend.clearCache()
        val baseline = OpenCodeLibOnigRegexBackend.allocationSnapshot()

        val direct =
            OpenCodeLibOnigRegexBackend.find(
                pattern = "answer",
                input = "answer",
                startIndex = 0,
                endIndex = 6,
            )
        val decomposed =
            OpenCodeLibOnigRegexBackend.find(
                pattern = "(?:foo|(?Z))",
                input = "foo",
                startIndex = 0,
                endIndex = 3,
            )

        assertEquals(0, direct?.start)
        assertEquals(0, decomposed?.start)
        val populated = OpenCodeLibOnigRegexBackend.allocationSnapshot()
        assertTrue(populated.allocations > baseline.allocations)
        assertTrue(populated.frees > baseline.frees, "Successful decomposition probes must be freed immediately")
        assertTrue(populated.live > 0, "Cached compiled expressions should remain live before cache clear")

        OpenCodeLibOnigRegexBackend.clearCache()
        val cleared = OpenCodeLibOnigRegexBackend.allocationSnapshot()
        assertEquals(0, cleared.live)
        assertEquals(cleared.allocations, cleared.frees)
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

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun benchmarkLongMarkdownHistoryLibOnigBackend() {
        val warmSampleCount =
            getenv("TEXTMATE_BENCHMARK_WARM_SAMPLES")
                ?.toKString()
                ?.toIntOrNull()
                ?.coerceIn(0, MAX_BENCHMARK_WARM_SAMPLE_COUNT)
                ?: 0
        val messages = longMarkdownHistory()
        OpenCodeLibOnigRegexBackend.clearCache()
        val metrics = SoakMetricsSink()
        val coldStartedAt = TimeSource.Monotonic.markNow()
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    OpenCodeTextMateGrammarBundleLoader
                        .loadBundled(bundledTestResourceReader(), metrics)
                        .bundle,
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeLibOnigRegexBackend, metrics),
                metricsSink = metrics,
            )
        val markdownCount = messages.count { OpenCodeMarkdownDetector.shouldRender(it) }

        messages.forEach { message ->
            if (OpenCodeMarkdownDetector.shouldRender(message)) {
                OpenCodeMarkdownParser.parse(message, highlighter)
            }
        }
        val coldTotalMs = coldStartedAt.elapsedNow().inWholeMilliseconds
        val warmSamplesMs =
            List(warmSampleCount) {
                measureTime {
                    messages.forEach { message ->
                        if (OpenCodeMarkdownDetector.shouldRender(message)) {
                            OpenCodeMarkdownParser.parse(message, highlighter)
                        }
                    }
                }.inWholeMilliseconds
            }

        assertTrue(coldTotalMs >= 0)
        assertEquals(20, markdownCount)
        println(
            "MARKDOWN_BENCHMARK kmp-libonig parser-ms=$coldTotalMs cold-ms=$coldTotalMs " +
                "warm-ms=${warmSamplesMs.joinToString(",")} warm-samples=$warmSampleCount " +
                "grammar-ms=${metrics.totalMilliseconds(TextMateMetricStage.GrammarLoad)} " +
                "regex-compile-ms=${metrics.totalMilliseconds(TextMateMetricStage.RegexCompile)} " +
                "regex-search-ms=${metrics.totalMilliseconds(TextMateMetricStage.RegexSearch)} " +
                "regex-search-max-ms=${metrics.maximumMilliseconds(TextMateMetricStage.RegexSearch)} " +
                "native-regex-search-max-ms=${metrics.maximumMilliseconds(TextMateMetricStage.NativeRegexSearch)} " +
                "tokenize-ms=${metrics.totalMilliseconds(TextMateMetricStage.Tokenize)} " +
                "repair-ms=${metrics.totalMilliseconds(TextMateMetricStage.Repair)} " +
                "messages=20 code-lines=3000",
        )

        OpenCodeLibOnigRegexBackend.clearCache()
        val cleared = OpenCodeLibOnigRegexBackend.allocationSnapshot()
        assertEquals(0, cleared.live)
        assertEquals(cleared.allocations, cleared.frees)
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun mixedLanguageSoakWhenRequested() {
        val durationSeconds = getenv("TEXTMATE_SOAK_SECONDS")?.toKString()?.toLongOrNull() ?: 0L
        if (durationSeconds <= 0L) {
            println("TEXTMATE_SOAK skipped; set TEXTMATE_SOAK_SECONDS to run the opt-in soak")
            return
        }

        OpenCodeLibOnigRegexBackend.clearCache()
        val metrics = SoakMetricsSink()
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle =
                    OpenCodeTextMateGrammarBundleLoader
                        .loadBundled(bundledTestResourceReader(), metrics)
                        .bundle,
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(OpenCodeLibOnigRegexBackend, metrics),
                metricsSink = metrics,
            )
        val workload = mixedLanguageWorkload()

        repeat(SOAK_WARM_UP_CYCLES) {
            parseCycle(workload, highlighter)
        }
        val retainedBaseline = OpenCodeLibOnigRegexBackend.allocationSnapshot()
        var peakLiveExpressions = retainedBaseline.live
        var cycles = 0
        val startedAt = TimeSource.Monotonic.markNow()

        while (startedAt.elapsedNow().inWholeSeconds < durationSeconds) {
            parseCycle(workload, highlighter)
            cycles += 1
            peakLiveExpressions =
                maxOf(
                    peakLiveExpressions,
                    OpenCodeLibOnigRegexBackend.allocationSnapshot().live,
                )
        }

        val retainedFinal = OpenCodeLibOnigRegexBackend.allocationSnapshot()
        val elapsedMs = startedAt.elapsedNow().inWholeMilliseconds
        assertTrue(cycles > 0, "The soak must complete at least one mixed-language cycle")
        assertEquals(
            retainedBaseline.live,
            retainedFinal.live,
            "The libonig cache must stop growing after the warm-up cycles",
        )

        OpenCodeLibOnigRegexBackend.clearCache()
        val cleared = OpenCodeLibOnigRegexBackend.allocationSnapshot()
        assertEquals(0, cleared.live)
        assertEquals(cleared.allocations, cleared.frees)
        println(
            "TEXTMATE_SOAK platform=ios-libonig duration-ms=$elapsedMs cycles=$cycles " +
                "warmup-cycles=$SOAK_WARM_UP_CYCLES retained-live=${retainedFinal.live} " +
                "peak-live=$peakLiveExpressions allocations=${cleared.allocations} " +
                "frees=${cleared.frees} native-search-count=${metrics.nativeSearchCount} " +
                "native-search-max-ms=${metrics.nativeSearchMaximumNanoseconds / 1_000_000.0}",
        )
    }

    private fun parseCycle(
        workload: List<String>,
        highlighter: OpenCodeSyntaxHighlighter,
    ) {
        workload.forEach { markdown ->
            val document = OpenCodeMarkdownParser.parse(markdown, highlighter)
            assertTrue(document.blocks.any { block -> block is MarkdownBlock.CodeBlock })
        }
    }

    private class SoakMetricsSink : OpenCodeTextMateMetricsSink {
        private val totalNanoseconds = LongArray(TextMateMetricStage.entries.size)
        private val maximumNanoseconds = LongArray(TextMateMetricStage.entries.size)

        var nativeSearchCount: Long = 0
            private set
        var nativeSearchMaximumNanoseconds: Long = 0
            private set

        override fun record(event: TextMateMetricEvent) {
            val index = event.stage.ordinal
            totalNanoseconds[index] += event.durationNanoseconds
            maximumNanoseconds[index] = maxOf(maximumNanoseconds[index], event.durationNanoseconds)
            if (event.stage == TextMateMetricStage.NativeRegexSearch) {
                nativeSearchCount += 1
                nativeSearchMaximumNanoseconds =
                    maxOf(nativeSearchMaximumNanoseconds, event.durationNanoseconds)
            }
        }

        fun totalMilliseconds(stage: TextMateMetricStage): Double = totalNanoseconds[stage.ordinal].toDouble() / 1_000_000.0

        fun maximumMilliseconds(stage: TextMateMetricStage): Double = maximumNanoseconds[stage.ordinal].toDouble() / 1_000_000.0
    }

    private fun mixedLanguageWorkload(): List<String> =
        listOf(
            markdown("typescript") { index -> "const item$index = response$index?.value ?? 'soak';" },
            markdown("kotlin") { index -> "val item$index = response$index?.value ?: \"soak\"" },
            markdown("swift") { index -> "let item$index = response$index?.value ?? \"soak\"" },
            markdown("python") { index -> "item_$index = response_$index.value if response_$index else 'soak'" },
            markdown("json") { index -> "\"item$index\": { \"value\": $index, \"enabled\": true }," },
            markdown("shellscript") { index -> "item_$index=\"\${response_$index:-soak}\"" },
        )

    private fun markdown(
        language: String,
        line: (Int) -> String,
    ): String =
        buildString {
            append("## TextMate soak\n\n```")
            append(language)
            append('\n')
            repeat(SOAK_LINES_PER_LANGUAGE) { index ->
                append(line(index))
                append('\n')
            }
            append("```\n")
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

    private companion object {
        const val MAX_BENCHMARK_WARM_SAMPLE_COUNT = 20
        const val SOAK_WARM_UP_CYCLES = 5
        const val SOAK_LINES_PER_LANGUAGE = 40
    }
}
