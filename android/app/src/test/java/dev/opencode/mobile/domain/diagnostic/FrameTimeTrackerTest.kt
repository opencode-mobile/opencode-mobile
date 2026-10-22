package dev.opencode.mobile.domain.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTimeTrackerTest {
    private val frame60 = 1.0 / 60.0
    private val frame120 = 1.0 / 120.0

    @Test
    fun visibleOnlyWhenUnlockedAndEnabled() {
        assertTrue(PerformanceOverlayGate.isVisible(developerToolsUnlocked = true, enabled = true))
        assertFalse(PerformanceOverlayGate.isVisible(developerToolsUnlocked = true, enabled = false))
        assertFalse(PerformanceOverlayGate.isVisible(developerToolsUnlocked = false, enabled = true))
        assertFalse(PerformanceOverlayGate.isVisible(developerToolsUnlocked = false, enabled = false))
    }

    @Test
    fun firstSample_isIdle() {
        val snapshot = FrameTimeTracker().record(timestampSeconds = 1.0, expectedIntervalSeconds = frame60)

        assertEquals(FrameTimeSnapshot.idle, snapshot)
        assertEquals("— fps\n— ms\nspikes 0\nworst —", snapshot.label)
    }

    @Test
    fun steady60Hz_reportsRefreshRateWithoutSpikes() {
        val tracker = FrameTimeTracker()
        var snapshot = FrameTimeSnapshot.idle
        repeat(41) { index ->
            snapshot = tracker.record(timestampSeconds = index * frame60, expectedIntervalSeconds = frame60)
        }

        assertEquals(60, snapshot.framesPerSecond)
        assertEquals(17, snapshot.frameMilliseconds)
        assertEquals(0, snapshot.spikeCount)
        assertNull(snapshot.worstSpikeMilliseconds)
        assertFalse(snapshot.spikeActive)
    }

    @Test
    fun frameUnderTwiceVsync_isNotASpike() {
        val tracker = FrameTimeTracker()
        tracker.record(timestampSeconds = 0.0, expectedIntervalSeconds = frame60)

        val snapshot = tracker.record(timestampSeconds = 0.020, expectedIntervalSeconds = frame60)

        assertEquals(0, snapshot.spikeCount)
        assertEquals(20, snapshot.frameMilliseconds)
    }

    @Test
    fun frameAtLeastTwiceVsync_isASpike() {
        val tracker = FrameTimeTracker()
        tracker.record(timestampSeconds = 0.0, expectedIntervalSeconds = frame60)

        val snapshot = tracker.record(timestampSeconds = 0.050, expectedIntervalSeconds = frame60)

        assertEquals(1, snapshot.spikeCount)
        assertEquals(50, snapshot.worstSpikeMilliseconds)
        assertEquals(50, snapshot.frameMilliseconds)
        assertTrue(snapshot.spikeActive)
        assertEquals("20 fps\n50 ms\nspikes 1\nworst 50 ms", snapshot.label)
    }

    @Test
    fun sameGap_isASpikeAt120Hz() {
        val tracker = FrameTimeTracker()
        tracker.record(timestampSeconds = 0.0, expectedIntervalSeconds = frame120)

        val snapshot = tracker.record(timestampSeconds = 0.020, expectedIntervalSeconds = frame120)

        assertEquals(1, snapshot.spikeCount)
        assertTrue(snapshot.spikeActive)
    }

    @Test
    fun highlightEnds_butWorstSpikeStays() {
        val tracker = FrameTimeTracker()
        tracker.record(timestampSeconds = 0.0, expectedIntervalSeconds = frame60)
        tracker.record(timestampSeconds = 0.050, expectedIntervalSeconds = frame60)

        var timestamp = 0.050
        var snapshot = FrameTimeSnapshot.idle
        val end = 0.050 + FrameTimeTracker.SPIKE_HIGHLIGHT_SECONDS + frame60
        while (timestamp < end - 0.000_001) {
            timestamp = minOf(timestamp + frame60, end)
            snapshot = tracker.record(timestampSeconds = timestamp, expectedIntervalSeconds = frame60)
        }

        assertFalse(snapshot.spikeActive)
        assertEquals(1, snapshot.spikeCount)
        assertEquals(50, snapshot.worstSpikeMilliseconds)
    }

    @Test
    fun suspendedGap_isNotASpike() {
        val tracker = FrameTimeTracker()
        tracker.record(timestampSeconds = 0.0, expectedIntervalSeconds = frame60)
        tracker.record(timestampSeconds = frame60, expectedIntervalSeconds = frame60)

        val snapshot =
            tracker.record(
                timestampSeconds = frame60 + FrameTimeTracker.SUSPENDED_GAP_SECONDS,
                expectedIntervalSeconds = frame60,
            )

        assertEquals(0, snapshot.spikeCount)
        assertNull(snapshot.worstSpikeMilliseconds)
        assertNull(snapshot.framesPerSecond)
    }

    @Test
    fun invalidIntervalAndBackwardsTimestamp_areIgnored() {
        val tracker = FrameTimeTracker()

        val invalid = tracker.record(timestampSeconds = 1.0, expectedIntervalSeconds = 0.0)
        val backwards = tracker.record(timestampSeconds = 0.5, expectedIntervalSeconds = frame60)

        assertEquals(0, invalid.spikeCount)
        assertEquals(0, backwards.spikeCount)
        assertNull(backwards.frameMilliseconds)
    }
}
