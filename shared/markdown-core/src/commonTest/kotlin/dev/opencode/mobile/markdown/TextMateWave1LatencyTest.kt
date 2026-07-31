package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextMateWave1LatencyTest {
    @Test
    fun accumulatorSkipsPerSearchStagesByDefault() {
        val metrics = OpenCodeTextMateMetricsAccumulator()
        metrics.record(
            TextMateMetricEvent(
                stage = TextMateMetricStage.Tokenize,
                outcome = TextMateMetricOutcome.Success,
                durationNanoseconds = 1_000,
            ),
        )
        metrics.record(
            TextMateMetricEvent(
                stage = TextMateMetricStage.RegexSearch,
                outcome = TextMateMetricOutcome.Success,
                durationNanoseconds = 1_000,
            ),
        )
        metrics.record(
            TextMateMetricEvent(
                stage = TextMateMetricStage.NativeRegexSearch,
                outcome = TextMateMetricOutcome.Success,
                durationNanoseconds = 1_000,
            ),
        )
        val stages = metrics.stageSummaries().map { it.stage }.toSet()
        assertTrue(TextMateMetricStage.Tokenize in stages)
        assertTrue(TextMateMetricStage.RegexSearch !in stages)
        assertTrue(TextMateMetricStage.NativeRegexSearch !in stages)
    }

    @Test
    fun accumulatorRecordsPerSearchWhenEnabled() {
        val metrics = OpenCodeTextMateMetricsAccumulator(recordPerSearch = true)
        metrics.record(
            TextMateMetricEvent(
                stage = TextMateMetricStage.RegexSearch,
                outcome = TextMateMetricOutcome.Success,
                durationNanoseconds = 5,
            ),
        )
        assertEquals(1, metrics.stageSummaries().single().eventCount)
    }

    @Test
    fun earliestMatchShortCircuitStopsAfterCursorHit() {
        val events = mutableListOf<TextMateMetricEvent>()
        val sink = OpenCodeTextMateMetricsSink(events::add)
        val grammar =
            TextMateGrammar(
                name = "short-circuit",
                scopeName = "source.short-circuit",
                patterns =
                    listOf(
                        TextMatePattern(name = "kw.first", match = "\\bfirst\\b"),
                        TextMatePattern(name = "kw.a", match = "\\balpha\\b"),
                        TextMatePattern(name = "kw.b", match = "\\bbeta\\b"),
                        TextMatePattern(name = "kw.c", match = "\\bgamma\\b"),
                        TextMatePattern(name = "kw.d", match = "\\bdelta\\b"),
                        TextMatePattern(name = "kw.e", match = "\\bepsilon\\b"),
                        TextMatePattern(name = "kw.f", match = "\\bzeta\\b"),
                        TextMatePattern(name = "kw.g", match = "\\beta\\b"),
                        TextMatePattern(name = "kw.h", match = "\\btheta\\b"),
                        TextMatePattern(name = "kw.i", match = "\\biota\\b"),
                    ),
            )
        val descriptor =
            TextMateGrammarDescriptor(
                name = "short-circuit",
                displayName = "Short Circuit",
                scopeName = grammar.scopeName,
                resourcePath = "short-circuit.json",
                embeddedLanguages = emptyList(),
                aliases = emptyList(),
            )
        val payload = TextMateGrammarPayload(descriptor, grammar, GrammarId(0))
        val tokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend, sink)

        val result = tokenizer.tokenizeLine(payload, "first")
        assertTrue(result.tokens.isNotEmpty())

        val searches = events.count { it.stage == TextMateMetricStage.RegexSearch }
        // Without short-circuit this line would try all 10 rules. With it, the
        // first rule matches at cursor 0 and remaining rules are skipped.
        assertTrue(searches < 10, "expected short-circuit to skip trailing rules, searches=$searches")
        assertTrue(searches >= 1)
    }
}
