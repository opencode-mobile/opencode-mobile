package dev.opencode.mobile.domain.event

import kotlin.math.min
import kotlin.random.Random

internal object SseRetryBackoff {
    private const val CAP_MS = 15_000L
    private const val BASE_MS = 1_000L

    fun delayMs(
        attempt: Int,
        random: Random = Random.Default,
    ): Long {
        val exponent = (attempt - 1).coerceIn(0, 3)
        val raw = BASE_MS shl exponent
        val capped = min(CAP_MS, raw)
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
