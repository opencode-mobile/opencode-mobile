package dev.opencode.voiceengine

import android.os.SystemClock

/**
 * How long a download has left, from its speed smoothed over the last several seconds. Says nothing for its first
 * [WARM_UP_MS], so bytes resumed from an earlier attempt and a slow start don't count as speed, nor while it's stalled.
 */
class DownloadRate(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var startedAt = -1L
    private var lastAt = 0L
    private var lastDone = 0L
    private var speed = 0.0 // bytes per ms

    fun secondsLeft(
        done: Long,
        total: Long,
    ): Long? {
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
        return ((total - done).coerceAtLeast(0) / speed / 1_000).toLong()
    }

    companion object {
        const val WARM_UP_MS = 3_000L
        private const val SAMPLE_MS = 500L
        private const val SMOOTHING = 0.2
    }
}
