package dev.opencode.voiceengine

import android.os.SystemClock

/**
 * A download's speed, smoothed over the last several seconds, and how long it has left. Says nothing for its first
 * [WARM_UP_MS], so bytes resumed from an earlier attempt and a slow start don't count as speed, nor while it's stalled.
 */
class DownloadRate(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    /** The smoothed speed and the time left at it, for "8.4 MB/s · about 2 min left". */
    data class Estimate(
        val bytesPerSecond: Long,
        val secondsLeft: Long,
    )

    private var startedAt = -1L
    private var lastAt = 0L
    private var lastDone = 0L
    private var speed = 0.0 // bytes per ms

    fun secondsLeft(
        done: Long,
        total: Long,
    ): Long? = estimate(done, total)?.secondsLeft

    /** Takes in that [done] of [total] bytes are on the phone now; null until the speed is known. */
    fun estimate(
        done: Long,
        total: Long,
    ): Estimate? {
        val at = now()
        if (startedAt < 0) {
            startedAt = at
            lastAt = at
            lastDone = done
            return null
        }
        val elapsed = at - lastAt
        if (elapsed >= SAMPLE_MS) {
            val sample = (done - lastDone).coerceAtLeast(0).toDouble() / elapsed
            speed = if (speed == 0.0) sample else speed + SMOOTHING * (sample - speed)
            lastAt = at
            lastDone = done
        }
        if (at - startedAt < WARM_UP_MS || speed <= 0.0) return null
        return Estimate((speed * 1_000).toLong(), ((total - done).coerceAtLeast(0) / speed / 1_000).toLong())
    }

    companion object {
        const val WARM_UP_MS = 3_000L
        private const val SAMPLE_MS = 500L
        private const val SMOOTHING = 0.2
    }
}
