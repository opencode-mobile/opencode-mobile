package dev.opencode.mobile.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
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
            val stream = FakeForegroundEventStream()
            val coordinator =
                ForegroundEventLifecycleCoordinator(
                    foregroundEventStream = stream,
                    hasActiveConnection = { true },
                    scope = this,
                    stopGraceMs = 5_000L,
                )

            coordinator.onStart(owner)
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)

            coordinator.onStop(owner)
            runCurrent()
            advanceTimeBy(1_000L)
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)

            coordinator.onStart(owner)
            runCurrent()
            advanceTimeBy(10_000L)
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
        }

    @Test
    fun sustainedOnStopStopsStreamAfterGrace() =
        runTest(testDispatcher) {
            val stream = FakeForegroundEventStream()
            val coordinator =
                ForegroundEventLifecycleCoordinator(
                    foregroundEventStream = stream,
                    hasActiveConnection = { true },
                    scope = this,
                    stopGraceMs = 5_000L,
                )

            coordinator.onStart(owner)
            coordinator.onStop(owner)
            runCurrent()
            advanceTimeBy(4_999L)
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(EventStreamConnectionState.Idle, stream.connectionState.value)
        }
}
