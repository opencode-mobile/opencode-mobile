package dev.opencode.mobile.domain.event

import kotlin.random.Random

/** Exponential SSE reconnect delay: 1, 2, 4, 8s (attempt 4 onward stays at 8s) with ±25% jitter. */
internal object SseRetryBackoff {
    private const val BASE_MS = 1_000L

    fun delayMs(
        attempt: Int,
        random: Random = Random.Default,
    ): Long {
        val exponent = (attempt - 1).coerceIn(0, 3)
        val capped = BASE_MS shl exponent
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
