package dev.opencode.mobile.domain.textmate

import android.content.ComponentCallbacks2
import dev.opencode.mobile.markdown.CodeBlockKey
import dev.opencode.mobile.markdown.HighlightIntent
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightResult
import dev.opencode.mobile.markdown.HighlightRevision
import dev.opencode.mobile.markdown.MemoryPressure
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateParseOutcome
import dev.opencode.mobile.markdown.TextMateRequestScheduler
import dev.opencode.mobile.markdown.TextMateSchedulingReason
import dev.opencode.mobile.markdown.emptyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextMateProcessPressureObserverTest {
    @Suppress("DEPRECATION")
    @Test
    fun trimLevelsMapPerLadder() {
        assertNull(memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(MemoryPressure.Moderate, memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(MemoryPressure.Critical, memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(MemoryPressure.Moderate, memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(MemoryPressure.Moderate, memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals(MemoryPressure.Moderate, memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertEquals(MemoryPressure.Critical, memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun forwarderSendsTrimsAndNormalOnForeground() {
        val engine = RecordingEngine()
        val forwarder = TextMatePressureForwarder(engine, CoroutineScope(UnconfinedTestDispatcher()))

        assertTrue(forwarder.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        @Suppress("DEPRECATION")
        assertFalse(forwarder.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        forwarder.onLowMemory()
        forwarder.onForeground()

        assertEquals(
            listOf(MemoryPressure.Moderate, MemoryPressure.Critical, MemoryPressure.Normal),
            engine.trims,
        )
    }

    @Test
    fun backgroundAndReturnKeepsHistoryHighlighting() =
        runBlocking {
            val delegate = RecordingEngine()
            val engine =
                AndroidTextMateHighlightingEngine(
                    delegate = delegate,
                    ownerDispatcher = Dispatchers.Unconfined,
                    scheduler = TextMateRequestScheduler(),
                )
            val forwarder = TextMatePressureForwarder(engine, CoroutineScope(UnconfinedTestDispatcher()))

            forwarder.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            forwarder.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            val whileHidden = engine.highlight(request("hidden", HighlightIntent.BackgroundHistory))
            assertEquals(TextMateParseOutcome.BudgetExceeded, whileHidden.outcome)
            assertEquals(TextMateSchedulingReason.Trim.name, whileHidden.observation.schedulingReason)
            assertEquals(
                TextMateParseOutcome.Highlighted,
                engine.highlight(request("visible", HighlightIntent.VisibleHistory)).outcome,
            )

            forwarder.onForeground()

            assertEquals(
                TextMateParseOutcome.Highlighted,
                engine.highlight(request("returned", HighlightIntent.BackgroundHistory)).outcome,
            )
        }

    @Test
    fun foregroundNormalDoesNotBuildTheEngine() =
        runBlocking {
            var built = false
            val engine =
                AndroidTextMateHighlightingEngine(
                    delegateFactory = {
                        built = true
                        RecordingEngine()
                    },
                    ownerDispatcher = Dispatchers.Unconfined,
                )

            engine.trim(MemoryPressure.Normal)

            assertFalse(built)
        }

    private fun request(
        key: String,
        intent: HighlightIntent,
    ): HighlightRequest =
        HighlightRequest(
            key = CodeBlockKey(key),
            revision = HighlightRevision(1),
            language = "kotlin",
            source = "val x = 1",
            intent = intent,
            policyEpoch = 1,
        )

    private class RecordingEngine : TextMateHighlightingEngine {
        val trims = mutableListOf<MemoryPressure>()

        override suspend fun highlight(request: HighlightRequest): HighlightResult =
            emptyResult(
                request = request,
                outcome = TextMateParseOutcome.Highlighted,
                queueWaitNanoseconds = 0,
                schedulingReason = null,
            )

        override suspend fun trim(level: MemoryPressure) {
            trims += level
        }
    }
}
