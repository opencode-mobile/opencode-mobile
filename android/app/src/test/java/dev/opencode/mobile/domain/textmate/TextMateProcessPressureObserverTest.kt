package dev.opencode.mobile.domain.textmate

import android.content.ComponentCallbacks2
import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightResult
import dev.opencode.mobile.markdown.MemoryPressure
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import dev.opencode.mobile.markdown.TextMateParseOutcome
import dev.opencode.mobile.markdown.emptyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun forwarderSendsTrims() {
        val engine = RecordingEngine()
        val forwarder = TextMatePressureForwarder(engine, CoroutineScope(UnconfinedTestDispatcher()))

        assertTrue(forwarder.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        @Suppress("DEPRECATION")
        assertFalse(forwarder.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        forwarder.onLowMemory()

        assertEquals(listOf(MemoryPressure.Moderate, MemoryPressure.Critical), engine.trims)
    }

    private class RecordingEngine : TextMateHighlightingEngine {
        val trims = mutableListOf<MemoryPressure>()

        override suspend fun highlight(request: HighlightRequest): HighlightResult = emptyResult(request, TextMateParseOutcome.Highlighted)

        override suspend fun trim(level: MemoryPressure) {
            trims += level
        }
    }
}
