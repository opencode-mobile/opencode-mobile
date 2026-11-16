package dev.opencode.hexcode.home

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DictationThreadsTest {
    @Test
    fun closeCancelsBothScopesAndShutsBothThreadsDown() {
        val threads = DictationThreads()
        threads.close()
        assertFalse(threads.scope.isActive)
        assertFalse(threads.vadScope.isActive)
        assertTrue(threads.audioExecutor.isShutdown)
        assertTrue(threads.vadExecutor.isShutdown)
        assertTrue(threads.audioExecutor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(threads.vadExecutor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun theLastStepRunsOnTheAudioThreadAfterTheBlockRunningThere() {
        val threads = DictationThreads()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val running = CountDownLatch(1)
        val finish = CountDownLatch(1)
        threads.scope.launch {
            running.countDown()
            // Like the microphone starting: blocks the audio thread, and isn't cancellable.
            finish.await()
            order += "block"
        }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        threads.close { order += "last on ${Thread.currentThread().name}" }
        assertEquals(emptyList<String>(), order.toList())
        finish.countDown()
        assertTrue(threads.audioExecutor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("block", "last on hex-audio"), order.toList())
    }

    @Test
    fun nothingStartsOnTheScopesAfterClose() {
        val threads = DictationThreads()
        threads.close()
        var ran = false
        threads.scope.launch { ran = true }
        threads.vadScope.launch { ran = true }
        assertTrue(threads.audioExecutor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(threads.vadExecutor.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(ran)
    }

    @Test
    fun closingTwiceIsSafeAndRunsTheLastStepOnce() {
        val threads = DictationThreads()
        val runs = Collections.synchronizedList(mutableListOf<Int>())
        threads.close { runs += 1 }
        threads.close { runs += 2 }
        assertTrue(threads.audioExecutor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf(1), runs.toList())
    }

    @Test
    fun aFailingLastStepStillShutsDown() {
        val threads = DictationThreads()
        threads.close { error("the recorder broke") }
        assertTrue(threads.audioExecutor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(threads.vadExecutor.awaitTermination(5, TimeUnit.SECONDS))
    }
}
