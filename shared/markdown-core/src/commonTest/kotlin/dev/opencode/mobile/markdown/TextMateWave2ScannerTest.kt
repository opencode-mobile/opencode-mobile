package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TextMateWave2ScannerTest {
    @Test
    fun findEarliestPrefersAtCursorMatchWithoutScanningWholeLine() {
        val events = mutableListOf<TextMateMetricEvent>()
        val sink = OpenCodeTextMateMetricsSink(events::add)
        val backend = CountingFindBackend(KotlinRegexTextMateRegexBackend, events)
        val buffer = PreparedTextMateLineBuffer.prepare("alpha beta gamma", sink)
        val scanned =
            backend.findEarliest(
                candidates =
                    listOf(
                        TextMateRegexScanCandidate(0, "\\balpha\\b"),
                        TextMateRegexScanCandidate(1, "\\bbeta\\b"),
                        TextMateRegexScanCandidate(2, "\\bgamma\\b"),
                    ),
                buffer = buffer,
                startIndex = 0,
                endIndex = buffer.length,
                metricsSink = sink,
            )
        assertNotNull(scanned)
        assertEquals(0, scanned.id)
        assertEquals(0, scanned.match.start)
        assertEquals(5, scanned.match.end)
        // Pass 1 should stop after the first at-cursor hit.
        assertEquals(1, backend.findCalls.size)
        assertEquals(1, backend.findCalls.single().endIndex)
    }

    @Test
    fun findEarliestTightensWindowOnceLaterMatchIsKnown() {
        val events = mutableListOf<TextMateMetricEvent>()
        val sink = OpenCodeTextMateMetricsSink(events::add)
        val backend = CountingFindBackend(KotlinRegexTextMateRegexBackend, events)
        val buffer = PreparedTextMateLineBuffer.prepare("....beta", sink)
        val scanned =
            backend.findEarliest(
                candidates =
                    listOf(
                        TextMateRegexScanCandidate(0, "nomatch"),
                        TextMateRegexScanCandidate(1, "\\bbeta\\b"),
                        TextMateRegexScanCandidate(2, "\\bgamma\\b"),
                    ),
                buffer = buffer,
                startIndex = 0,
                endIndex = buffer.length,
                metricsSink = sink,
            )
        assertNotNull(scanned)
        assertEquals(1, scanned.id)
        assertEquals(4, scanned.match.start)
        // After pass 1 misses, pass 2 should still find beta; gamma searched with
        // a tightened end so it cannot start after beta.
        assertTrue(backend.findCalls.size >= 3)
        assertTrue(
            backend.findCalls.any { call ->
                call.pattern == "\\bgamma\\b" && call.endIndex <= 5
            },
            "gamma search should be capped at/before beta start+1: ${backend.findCalls}",
        )
    }

    @Test
    fun tokenizerUsesScannerForMultiRuleLine() {
        val events = mutableListOf<TextMateMetricEvent>()
        val sink = OpenCodeTextMateMetricsSink(events::add)
        val grammar =
            TextMateGrammar(
                name = "scan",
                scopeName = "source.scan",
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
                name = "scan",
                displayName = "Scan",
                scopeName = grammar.scopeName,
                resourcePath = "scan.json",
                embeddedLanguages = emptyList(),
                aliases = emptyList(),
            )
        val payload = TextMateGrammarPayload(descriptor, grammar, GrammarId(0))
        val tokenizer = OpenCodeTextMateNativeTokenizer(KotlinRegexTextMateRegexBackend, sink)
        val result = tokenizer.tokenizeLine(payload, "first")
        assertTrue(result.tokens.isNotEmpty())
        val searches = events.count { it.stage == TextMateMetricStage.RegexSearch }
        assertTrue(searches < 10, "scanner should avoid probing every rule fully, searches=$searches")
    }

    private class CountingFindBackend(
        private val delegate: OpenCodeTextMateRegexBackend,
        private val events: MutableList<TextMateMetricEvent>,
    ) : OpenCodeTextMateRegexBackend {
        data class FindCall(
            val pattern: String,
            val startIndex: Int,
            val endIndex: Int,
        )

        val findCalls = mutableListOf<FindCall>()

        override val name: String = "counting-${delegate.name}"

        override fun find(
            pattern: String,
            buffer: PreparedTextMateLineBuffer,
            startIndex: Int,
            endIndex: Int,
            contiguousAnchor: Int?,
            metricsSink: OpenCodeTextMateMetricsSink,
        ): TextMateRegexMatch? {
            findCalls += FindCall(pattern, startIndex, endIndex)
            val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
            return try {
                delegate.find(pattern, buffer, startIndex, endIndex, contiguousAnchor, metricsSink)
            } finally {
                events +=
                    TextMateMetricEvent(
                        stage = TextMateMetricStage.RegexSearch,
                        outcome = TextMateMetricOutcome.Success,
                        durationNanoseconds = startedAt.elapsedNow().inWholeNanoseconds,
                        backend = name,
                    )
            }
        }
    }
}
