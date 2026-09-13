package dev.opencode.mobile.domain.event

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SseRetryBackoffTest {
    @Test
    fun backoffRespectsExponentialCapAndJitter() {
        repeat(20) { seed ->
            val random = Random(seed)
            val attempt4 = SseRetryBackoff.delayMs(4, random)
            assertTrue(attempt4 in 6_000L..10_000L)
            val attempt5 = SseRetryBackoff.delayMs(5, random)
            assertTrue(attempt5 in 6_000L..10_000L)
        }
    }

    @Test
    fun firstAttemptNearOneSecond() {
        val random = Random(1)
        val first = SseRetryBackoff.delayMs(1, random)
        assertTrue(first in 750L..1_250L)
    }
}
