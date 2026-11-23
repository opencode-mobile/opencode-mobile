package dev.opencode.mobile.domain.event

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectWakeSignalTest {
    @Test
    fun wakeBeforeWaiting_isNotLost() =
        runTest {
            val signal = ReconnectWakeSignal()
            val seen = signal.current()

            signal.wake()

            assertTrue(signal.awaitAfter(seen, timeoutMs = 60_000))
            assertEquals(0L, currentTime)
        }

    @Test
    fun wakeWhileWaiting_endsTheWaitEarly() =
        runTest {
            val signal = ReconnectWakeSignal()
            val waiting = async { signal.awaitAfter(signal.current(), timeoutMs = 60_000) }
            runCurrent()

            advanceTimeBy(1_000)
            signal.wake()

            assertTrue(waiting.await())
            assertEquals(1_000L, currentTime)
        }

    @Test
    fun noNewWake_waitsForTheFullTimeout() =
        runTest {
            val signal = ReconnectWakeSignal()
            signal.wake()

            assertFalse(signal.awaitAfter(signal.current(), timeoutMs = 5_000))
            assertEquals(5_000L, currentTime)
        }

    @Test
    fun zeroTimeout_doesNotReportAWake() =
        runTest {
            val signal = ReconnectWakeSignal()
            val seen = signal.current()
            signal.wake()

            assertFalse(signal.awaitAfter(seen, timeoutMs = 0))
        }
}
