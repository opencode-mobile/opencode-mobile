package dev.opencode.mobile.domain.event

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldSignalsTest {
    @Test
    fun profileSwitched_marksStale() {
        assertTrue(WorldSignal.ProfileSwitched.marksStale())
        assertTrue(WorldSignal.BackgroundStop.marksStale())
        assertTrue(WorldSignal.PathLost.marksStale())
        assertFalse(WorldSignal.Foreground.marksStale())
        assertFalse(WorldSignal.Background.marksStale())
    }

    @Test
    fun canCatchUp_requiresActiveConnectedOccupancy() {
        val connected =
            LiveConnectionSnapshot(connectionState = EventStreamConnectionState.Connected)
        val connecting =
            LiveConnectionSnapshot(connectionState = EventStreamConnectionState.Connecting)
        val reconnecting =
            LiveConnectionSnapshot(connectionState = EventStreamConnectionState.Reconnecting)

        assertFalse(canCatchUp(StreamOccupancy.Stopped, connected))
        assertFalse(canCatchUp(StreamOccupancy.Grace, connected))
        assertFalse(canCatchUp(StreamOccupancy.Active, connecting))
        assertTrue(canCatchUp(StreamOccupancy.Active, connected))
        assertTrue(canCatchUp(StreamOccupancy.Active, reconnecting))
    }
}
