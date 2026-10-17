package dev.opencode.mobile.domain.event

import kotlin.random.Random

/**
 * Exponential SSE reconnect delay: 1, 2, 4, 8s (attempt 4 onward stays at 8s) with ±25% jitter.
 * A rejected credential retries slower: 5, 10, 20, 30s (attempt 4 onward stays at 30s).
 */
internal object SseRetryBackoff {
    private const val BASE_MS = 1_000L
    private const val AUTH_BASE_MS = 5_000L
    private const val AUTH_CAP_MS = 30_000L

    fun delayMs(
        attempt: Int,
        random: Random = Random.Default,
    ): Long {
        val exponent = (attempt - 1).coerceIn(0, 3)
        return jittered(BASE_MS shl exponent, random)
    }

    fun authDelayMs(
        attempt: Int,
        random: Random = Random.Default,
    ): Long {
        val exponent = (attempt - 1).coerceIn(0, 3)
        return jittered((AUTH_BASE_MS shl exponent).coerceAtMost(AUTH_CAP_MS), random)
    }

    private fun jittered(
        capped: Long,
        random: Random,
    ): Long {
        val jitterSpan = (capped * 0.25).toLong()
        val jitter =
            if (jitterSpan == 0L) {
                0L
            } else {
                random.nextLong(-jitterSpan, jitterSpan + 1)
            }
        return (capped + jitter).coerceAtLeast(0L)
    }
}
