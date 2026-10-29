package dev.opencode.mobile.markdown

import dev.opencode.mobile.highlight.core.HighlightCancellation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HighlightCoreHighlightingEngineConcurrencyTest {
    private val source = (1..200).joinToString("\n") { "  \"key$it\": [$it, true, null, \"value\"]," }

    private fun request(
        source: String,
        revision: Long,
    ) = HighlightRequest(
        key = CodeBlockKey("fence"),
        revision = HighlightRevision(revision),
        language = "json",
        source = source,
        intent = HighlightIntent.InteractiveFinal,
        policyEpoch = 0,
    )

    /** Blocks the first call that reads it until [release], holding the engine's session. */
    private class Gate : HighlightCancellation {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile private var opened = false

        override val isCancelled: Boolean
            get() {
                if (!opened) {
                    opened = true
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                return false
            }
    }

    private fun runBehindRunningCall(
        engine: HighlightCoreHighlightingEngine,
        newer: HighlightRequest,
    ): Pair<HighlightCoreHighlightingEngine.Reported, HighlightCoreHighlightingEngine.Reported> {
        val gate = Gate()
        var older: HighlightCoreHighlightingEngine.Reported? = null
        var latest: HighlightCoreHighlightingEngine.Reported? = null
        val first = thread { older = engine.highlightReporting(request(source, revision = 1), gate) }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        val second = thread { latest = engine.highlightReporting(newer) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (engine.callsInFlight(newer.key) < 2 && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(2, engine.callsInFlight(newer.key))
        gate.release.countDown()
        first.join(5_000)
        second.join(5_000)
        assertEquals(0, engine.callsInFlight(newer.key))
        return requireNotNull(older) to requireNotNull(latest)
    }

    @Test
    fun aNewerRevisionWithTheSameTextWaitsAndReusesTheRunningResult() {
        val engine = HighlightCoreHighlightingEngine()

        val (older, newer) = runBehindRunningCall(engine, request(source, revision = 2))

        assertEquals(TextMateParseOutcome.Highlighted, older.result.outcome)
        assertEquals("Reuse", newer.mode)
        assertEquals(older.result.spans, newer.result.spans)
    }

    @Test
    fun aNewerRevisionWithDifferentTextCancelsTheRunningCallAndEditsItsSession() {
        val engine = HighlightCoreHighlightingEngine()
        val grown = "$source\n  \"tail\": 1"

        val (older, newer) = runBehindRunningCall(engine, request(grown, revision = 2))

        assertEquals(TextMateParseOutcome.Cancelled, older.result.outcome)
        assertEquals("Edit", newer.mode)
        assertEquals(TextMateParseOutcome.Highlighted, newer.result.outcome)
        val oneShot = HighlightCoreHighlightingEngine().highlightNow(request(grown, revision = 1)).spans
        assertEquals(oneShot, newer.result.spans)
    }
}
