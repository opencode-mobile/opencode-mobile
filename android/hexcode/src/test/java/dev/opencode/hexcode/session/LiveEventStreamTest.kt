package dev.opencode.hexcode.session

import dev.opencode.mobile.data.remote.SseAttemptOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** OpenCode Mobile's reconnect timing, the 35 s stall rule and waking (app-plan.md, Today's session on screen). */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveEventStreamTest {
    /** No jitter, so the waits are exact. */
    private object NoJitter : Random() {
        override fun nextBits(bitCount: Int) = 0

        override fun nextLong(
            from: Long,
            until: Long,
        ) = 0L
    }

    @Test
    fun aFailingStreamRetriesAfter1_2_4_8sThenEvery8s() =
        runTest {
            val times = mutableListOf<Long>()
            val stream =
                LiveEventStream(backgroundScope, { _, _ ->
                    times += testScheduler.currentTime
                    SseAttemptOutcome.Transport(null)
                }, random = NoJitter)
            stream.start()
            advanceTimeBy(40_000)
            runCurrent()
            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 23_000L, 31_000L, 39_000L), times)
        }

    @Test
    fun aRejectedPasswordRetriesSlower() =
        runTest {
            val times = mutableListOf<Long>()
            val stream =
                LiveEventStream(backgroundScope, { _, _ ->
                    times += testScheduler.currentTime
                    SseAttemptOutcome.Http(401)
                }, random = NoJitter)
            stream.start()
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(listOf(0L, 5_000L, 15_000L), times)
        }

    @Test
    fun wakeRetriesAtOnce() =
        runTest {
            var attempts = 0
            val stream =
                LiveEventStream(backgroundScope, { _, _ ->
                    attempts += 1
                    SseAttemptOutcome.Transport(null)
                }, random = NoJitter)
            stream.start()
            advanceTimeBy(3_500)
            runCurrent()
            assertEquals(3, attempts)
            stream.wake()
            runCurrent()
            assertEquals(4, attempts)
        }

    @Test
    fun aConnectionResetsTheBackoff() =
        runTest {
            val times = mutableListOf<Long>()
            var attempt = 0
            val stream =
                LiveEventStream(backgroundScope, { _, onLine ->
                    times += testScheduler.currentTime
                    attempt += 1
                    if (attempt == 3) onLine("""{"type":"server.connected","data":{}}""")
                    SseAttemptOutcome.Ended
                }, random = NoJitter)
            stream.start()
            advanceTimeBy(4_500)
            runCurrent()
            // The third attempt connected, so the fourth waits 1 s again, not 4 s.
            assertEquals(listOf(0L, 1_000L, 3_000L, 4_000L), times)
        }

    @Test
    fun silenceFor35sDropsTheConnection() =
        runTest {
            var attempts = 0
            var disconnects = 0
            val stream =
                LiveEventStream(backgroundScope, { onActivity, _ ->
                    attempts += 1
                    onActivity()
                    awaitCancellation()
                }, random = NoJitter)
            stream.onDisconnected = { disconnects += 1 }
            stream.start()
            advanceTimeBy(34_999)
            runCurrent()
            assertEquals(0, disconnects)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(1, disconnects)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, attempts)
        }

    @Test
    fun eventsArriveDecodedAndOtherTypesAreSkipped() =
        runTest {
            val events = mutableListOf<SessionEvent>()
            var connected = 0
            val stream =
                LiveEventStream(backgroundScope, { _, onLine ->
                    onLine("""{"type":"server.connected","data":{}}""")
                    onLine("""{"type":"session.step.started","data":{"sessionID":"s1"}}""")
                    onLine("""{"type":"session.execution.succeeded","data":{"sessionID":"s1"}}""")
                    awaitCancellation()
                }, random = NoJitter)
            stream.onConnected = { connected += 1 }
            stream.onEvent = { events += it }
            stream.start()
            runCurrent()
            assertEquals(1, connected)
            assertEquals(listOf<SessionEvent>(SessionEvent.ExecutionEnded("s1")), events)
            stream.stop()
        }
}
