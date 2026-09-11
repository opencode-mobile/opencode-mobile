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
    fun canCatchUp_requiresActiveFullyLiveLeases() {
        val connected =
            LiveConnectionSnapshot(
                connectionState = EventStreamConnectionState.Connected,
                requiredTargets = listOf("api/event"),
                liveTargets = listOf("api/event"),
            )
        val connecting =
            LiveConnectionSnapshot(
                connectionState = EventStreamConnectionState.Connecting,
                requiredTargets = listOf("api/event"),
                liveTargets = emptyList(),
            )
        val reconnecting =
            LiveConnectionSnapshot(
                connectionState = EventStreamConnectionState.Reconnecting,
                requiredTargets = listOf("api/event"),
                liveTargets = emptyList(),
            )
        val connectedWithoutLeases =
            LiveConnectionSnapshot(connectionState = EventStreamConnectionState.Connected)
        val partialV1 =
            LiveConnectionSnapshot(
                connectionState = EventStreamConnectionState.Connected,
                requiredTargets = listOf("global/event", "event:/repo"),
                liveTargets = listOf("global/event"),
            )
        val fullyLiveV1 =
            LiveConnectionSnapshot(
                connectionState = EventStreamConnectionState.Connected,
                requiredTargets = listOf("global/event", "event:/repo"),
                liveTargets = listOf("global/event", "event:/repo"),
            )

        assertFalse(canCatchUp(StreamOccupancy.Stopped, connected))
        assertFalse(canCatchUp(StreamOccupancy.Grace, connected))
        assertFalse(canCatchUp(StreamOccupancy.Active, connecting))
        assertFalse(canCatchUp(StreamOccupancy.Active, reconnecting))
        assertFalse(canCatchUp(StreamOccupancy.Active, connectedWithoutLeases))
        assertFalse(canCatchUp(StreamOccupancy.Active, partialV1))
        assertFalse(partialV1.isFullyLive())
        assertTrue(connected.isFullyLive())
        assertTrue(fullyLiveV1.isFullyLive())
        assertTrue(canCatchUp(StreamOccupancy.Active, connected))
        assertTrue(canCatchUp(StreamOccupancy.Active, fullyLiveV1))
    }
}
