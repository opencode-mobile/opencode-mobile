package dev.opencode.mobile.domain.diagnostic

import kotlin.math.roundToInt

/** Whether the performance HUD should be on screen. */
object PerformanceOverlayGate {
    fun isVisible(
        developerToolsUnlocked: Boolean,
        enabled: Boolean,
    ): Boolean = developerToolsUnlocked && enabled
}

/** One sample of the live frame-time HUD. */
data class FrameTimeSnapshot(
    val framesPerSecond: Int? = null,
    val frameMilliseconds: Int? = null,
    val spikeCount: Int = 0,
    val worstSpikeMilliseconds: Int? = null,
    val spikeActive: Boolean = false,
) {
    val label: String
        get() {
            val fps = framesPerSecond?.toString() ?: "—"
            val frame = frameMilliseconds?.let { "$it ms" } ?: "— ms"
            val worst = worstSpikeMilliseconds?.let { "$it ms" } ?: "—"
            return "$fps fps\n$frame\nspikes $spikeCount\nworst $worst"
        }

    companion object {
        val idle = FrameTimeSnapshot()
    }
}

/**
 * Turns display timestamps into FPS and hitch spikes.
 *
 * A spike is a frame at least [spikeMultiple] times the display vsync.
 * Gaps of [suspendedGapSeconds] or more are a resume, not a frame.
 */
class FrameTimeTracker {
    private var lastTimestamp: Double? = null
    private val recent = ArrayDeque<Sample>()
    private var spikeCount = 0
    private var worstSpikeSeconds = 0.0
    private var lastSpikeTimestamp: Double? = null
    private var lastFrameSeconds: Double? = null

    fun record(
        timestampSeconds: Double,
        expectedIntervalSeconds: Double,
    ): FrameTimeSnapshot {
        if (expectedIntervalSeconds <= 0.0 || expectedIntervalSeconds >= 0.1) {
            return snapshot(timestampSeconds, expectedIntervalSeconds)
        }
        val last = lastTimestamp
        if (last == null) {
            lastTimestamp = timestampSeconds
            return snapshot(timestampSeconds, expectedIntervalSeconds)
        }
        val delta = timestampSeconds - last
        lastTimestamp = timestampSeconds
        if (delta <= 0.0) {
            return snapshot(timestampSeconds, expectedIntervalSeconds)
        }
        if (delta >= SUSPENDED_GAP_SECONDS) {
            recent.clear()
            return snapshot(timestampSeconds, expectedIntervalSeconds)
        }
        recent.addLast(Sample(timestampSeconds, delta))
        val windowStart = timestampSeconds - SMOOTHING_WINDOW_SECONDS
        while (recent.firstOrNull()?.timestamp?.let { it < windowStart } == true) {
            recent.removeFirst()
        }
        lastFrameSeconds = delta
        if (delta >= expectedIntervalSeconds * SPIKE_MULTIPLE) {
            spikeCount += 1
            if (delta > worstSpikeSeconds) {
                worstSpikeSeconds = delta
            }
            lastSpikeTimestamp = timestampSeconds
        }
        return snapshot(timestampSeconds, expectedIntervalSeconds)
    }

    private fun snapshot(
        timestampSeconds: Double,
        expectedIntervalSeconds: Double,
    ): FrameTimeSnapshot =
        FrameTimeSnapshot(
            framesPerSecond = framesPerSecond(expectedIntervalSeconds),
            frameMilliseconds = lastFrameSeconds?.let(::milliseconds),
            spikeCount = spikeCount,
            worstSpikeMilliseconds = if (spikeCount > 0) milliseconds(worstSpikeSeconds) else null,
            spikeActive = lastSpikeTimestamp?.let { timestampSeconds - it <= SPIKE_HIGHLIGHT_SECONDS } == true,
        )

    private fun framesPerSecond(expectedIntervalSeconds: Double): Int? {
        if (recent.isEmpty()) return null
        val mean = recent.sumOf { it.delta } / recent.size
        if (mean <= 0.0) return null
        val raw = (1.0 / mean).roundToInt()
        if (expectedIntervalSeconds <= 0.0) return raw
        val refresh = (1.0 / expectedIntervalSeconds).roundToInt().coerceAtLeast(1)
        return minOf(raw, refresh)
    }

    private fun milliseconds(seconds: Double): Int = (seconds * 1_000).roundToInt()

    private data class Sample(
        val timestamp: Double,
        val delta: Double,
    )

    companion object {
        const val SPIKE_MULTIPLE = 2.0
        const val SMOOTHING_WINDOW_SECONDS = 0.5
        const val SPIKE_HIGHLIGHT_SECONDS = 1.0
        const val SUSPENDED_GAP_SECONDS = 2.0
    }
}
