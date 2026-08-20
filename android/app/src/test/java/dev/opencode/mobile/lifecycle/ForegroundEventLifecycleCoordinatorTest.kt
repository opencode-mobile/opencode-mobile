package dev.opencode.mobile.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.opencode.mobile.domain.event.FakeForegroundRestore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundEventLifecycleCoordinatorTest {
    private val testDispatcher = StandardTestDispatcher()
    private val owner =
        object : LifecycleOwner {
            override val lifecycle: Lifecycle
                get() = error("lifecycle unused by coordinator callbacks")
        }

    @Test
    fun briefOnStopDoesNotStopStreamWhenOnStartCancelsGrace() =
        runTest(testDispatcher) {
            val restore = FakeForegroundRestore()
            val coordinator =
                ForegroundEventLifecycleCoordinator(
                    foregroundRestore = restore,
                    hasActiveConnection = { true },
                    scope = this,
                    stopGraceMs = 5_000L,
                )

            coordinator.onStart(owner)
            assertEquals(1, restore.restoreCount)
            assertEquals(0, restore.backgroundStopCount)

            coordinator.onStop(owner)
            runCurrent()
            advanceTimeBy(1_000L)
            assertEquals(0, restore.backgroundStopCount)

            coordinator.onStart(owner)
            runCurrent()
            advanceTimeBy(10_000L)
            assertEquals(2, restore.restoreCount)
            assertEquals(0, restore.backgroundStopCount)
        }

    @Test
    fun sustainedOnStopStopsStreamAfterGrace() =
        runTest(testDispatcher) {
            val restore = FakeForegroundRestore()
            val coordinator =
                ForegroundEventLifecycleCoordinator(
                    foregroundRestore = restore,
                    hasActiveConnection = { true },
                    scope = this,
                    stopGraceMs = 5_000L,
                )

            coordinator.onStart(owner)
            coordinator.onStop(owner)
            runCurrent()
            advanceTimeBy(4_999L)
            assertEquals(0, restore.backgroundStopCount)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, restore.backgroundStopCount)
        }
}
