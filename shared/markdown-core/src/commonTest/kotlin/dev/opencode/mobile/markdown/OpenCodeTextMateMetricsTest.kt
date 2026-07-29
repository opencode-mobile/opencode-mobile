package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenCodeTextMateMetricsTest {
    @Test
    fun highlighterRecordsBoundedStageMetricsWithoutSourceIdentity() {
        val events = mutableListOf<TextMateMetricEvent>()
        val sink = OpenCodeTextMateMetricsSink(events::add)
        val descriptor =
            TextMateGrammarDescriptor(
                name = "phase-one-metrics",
                displayName = "Phase One Metrics",
                scopeName = "source.phase-one-metrics",
                resourcePath = "metrics.json",
                embeddedLanguages = emptyList(),
                aliases = emptyList(),
            )
        val grammar =
            TextMateGrammar(
                name = descriptor.name,
                scopeName = descriptor.scopeName,
                patterns =
                    listOf(
                        TextMatePattern(
                            match = "\\bphaseOneMetricUnique\\b",
                            name = "keyword.control.metrics",
                        ),
                    ),
            )
        val payload = TextMateGrammarPayload(descriptor, grammar, GrammarId(0))
        val manifest =
            TextMateGrammarManifest(
                schemaVersion = 1,
                generator = "test",
                shikiVersion = "test",
                languages =
                    listOf(
                        TextMateGrammarManifestEntry(
                            language = descriptor.name,
                            displayName = descriptor.displayName,
                            aliases = emptyList(),
                            grammarModule = "test",
                            grammars = listOf(descriptor),
                        ),
                    ),
            )
        val bundle = TextMateGrammarBundle.fromPreloaded(manifest, listOf(payload), metricsSink = sink)
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = bundle,
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend, sink),
                metricsSink = sink,
            )

        val result = highlighter.highlight(descriptor.name, "phaseOneMetricUnique")

        assertTrue(result.spans.isNotEmpty())
        val stages = events.mapTo(mutableSetOf()) { event -> event.stage }
        assertTrue(TextMateMetricStage.GrammarLoad in stages)
        assertTrue(TextMateMetricStage.GrammarLookup in stages)
        assertTrue(TextMateMetricStage.RuleResolve in stages)
        assertTrue(TextMateMetricStage.RegexCompile in stages)
        assertTrue(TextMateMetricStage.RegexSearch in stages)
        assertTrue(TextMateMetricStage.Tokenize in stages)
        assertTrue(TextMateMetricStage.Repair in stages)
        assertTrue(events.all { event -> event.backend?.contains("phaseOneMetricUnique") != true })
    }

    @Test
    fun observedParseReturnsOneRequestScopedSummaryPerFence() {
        val descriptor =
            TextMateGrammarDescriptor(
                name = "observed",
                displayName = "Observed",
                scopeName = "source.observed",
                resourcePath = "observed.json",
                embeddedLanguages = emptyList(),
                aliases = listOf("obs"),
            )
        val grammar =
            TextMateGrammar(
                name = descriptor.name,
                scopeName = descriptor.scopeName,
                patterns =
                    listOf(
                        TextMatePattern(
                            match = "\\bmetricToken\\b",
                            name = "keyword.control.observed",
                        ),
                    ),
            )
        val payload = TextMateGrammarPayload(descriptor, grammar, GrammarId(0))
        val manifest =
            TextMateGrammarManifest(
                schemaVersion = 1,
                generator = "test",
                shikiVersion = "test",
                languages =
                    listOf(
                        TextMateGrammarManifestEntry(
                            language = descriptor.name,
                            displayName = descriptor.displayName,
                            aliases = descriptor.aliases,
                            grammarModule = "test",
                            grammars = listOf(descriptor),
                        ),
                    ),
            )
        val highlighter =
            OpenCodeSyntaxHighlighter(
                grammarBundle = TextMateGrammarBundle.fromPreloaded(manifest, listOf(payload)),
                textMateTokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend),
            )
        val markdown =
            """
            Unicode 😀 message

            ```obs
            metricToken
            ```

            ```missing
            plain
            ```
            """.trimIndent()

        val envelope =
            OpenCodeMarkdownParser.parseObserved(
                markdown = markdown,
                codeHighlighter = highlighter,
                observeTextMate = true,
            )

        assertEquals(2, envelope.textMateObservations.size)
        val highlighted = envelope.textMateObservations[0]
        assertEquals("obs", highlighted.requestedLanguage)
        assertEquals("observed", highlighted.canonicalLanguage)
        assertEquals(TextMateParseOutcome.Highlighted, highlighted.outcome)
        assertEquals(markdown.length, highlighted.messageCharacters)
        assertEquals("metricToken".length, highlighted.fenceCharacters)
        assertEquals(1, highlighted.fenceIndex)
        assertEquals(2, highlighted.fenceCount)
        assertTrue(highlighted.spanCount > 0)
        assertNotNull(highlighted.backend)
        assertTrue(highlighted.totalNanoseconds >= 0)
        assertTrue(highlighted.stages.any { it.stage == TextMateMetricStage.Tokenize })

        val unsupported = envelope.textMateObservations[1]
        assertEquals(TextMateParseOutcome.Unsupported, unsupported.outcome)
        assertEquals(2, unsupported.fenceIndex)
        assertEquals(2, unsupported.fenceCount)
        assertTrue(unsupported.stages.any { it.stage == TextMateMetricStage.GrammarLookup })
        assertTrue(
            envelope.textMateObservations.none { observation ->
                observation.toString().contains("Unicode 😀 message")
            },
        )
    }

    @Test
    fun documentOnlyParseDoesNotCollectTextMateMeasurements() {
        val request =
            MarkdownParseRequest(
                markdown = "```kotlin\nval answer = 42\n```",
                textMateEnabled = true,
            )
        val response =
            OpenCodeMarkdownJson.parseMarkdownJson(
                OpenCodeMarkdownJson.json.encodeToString(MarkdownParseRequest.serializer(), request),
            )

        assertTrue(response.contains("\"blocks\""))
        assertTrue(!response.contains("textMateObservations"))
    }
}
