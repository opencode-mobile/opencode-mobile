package dev.opencode.mobile.domain.textmate

import android.content.ComponentCallbacks2
import dev.opencode.mobile.markdown.MemoryPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextMateProcessPressureObserverTest {
    @Test
    fun trimLevelsMapToEngineMemoryPressure() {
        val pressureFor = TextMateProcessPressureObserver::memoryPressureFor
        assertNull(pressureFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(MemoryPressure.Moderate, pressureFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(MemoryPressure.Critical, pressureFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(MemoryPressure.Critical, pressureFor(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
