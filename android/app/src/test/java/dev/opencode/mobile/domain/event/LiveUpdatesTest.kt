package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdatesTest {
    @Test
    fun occupyingIdleIsConnectingNotOffline() {
        val chrome =
            composeLiveUpdates(
                connectionState = EventStreamConnectionState.Idle,
                path = DeviceNetworkPathStatus.Ready,
                occupancy = StreamOccupancy.Active,
                hasConnection = true,
            )
        assertEquals(LiveUpdatesLabel.Connecting, chrome.label)
        assertTrue(chrome.canMutate)
    }

    @Test
    fun occupyingDisconnectedIsConnecting() {
        val chrome =
            composeLiveUpdates(
                connectionState = EventStreamConnectionState.Disconnected,
                path = DeviceNetworkPathStatus.Ready,
                occupancy = StreamOccupancy.Active,
                hasConnection = true,
            )
        assertEquals(LiveUpdatesLabel.Connecting, chrome.label)
        assertTrue(chrome.canMutate)
    }

    @Test
    fun connectedAndReadyIsLive() {
        val chrome =
            composeLiveUpdates(
                connectionState = EventStreamConnectionState.Connected,
                path = DeviceNetworkPathStatus.Ready,
                occupancy = StreamOccupancy.Active,
                hasConnection = true,
            )
        assertEquals(LiveUpdatesLabel.Live, chrome.label)
        assertTrue(chrome.canMutate)
    }

    @Test
    fun graceKeepsLiveWhileSocketStillUp() {
        val chrome =
            composeLiveUpdates(
                connectionState = EventStreamConnectionState.Connected,
                path = DeviceNetworkPathStatus.Ready,
                occupancy = StreamOccupancy.Grace,
                hasConnection = true,
            )
        assertEquals(LiveUpdatesLabel.Live, chrome.label)
        assertTrue(chrome.canMutate)
    }

    @Test
    fun backgroundStopIsOfflineAndBlocksMutations() {
        val chrome =
            composeLiveUpdates(
                connectionState = EventStreamConnectionState.Idle,
                path = DeviceNetworkPathStatus.Ready,
                occupancy = StreamOccupancy.Stopped,
                hasConnection = true,
            )
        assertEquals(LiveUpdatesLabel.Offline, chrome.label)
        assertFalse(chrome.canMutate)
    }

    @Test
    fun unavailablePathIsOfflineEvenIfSocketLooksConnected() {
        val chrome =
            composeLiveUpdates(
                connectionState = EventStreamConnectionState.Connected,
                path = DeviceNetworkPathStatus.Unavailable,
                occupancy = StreamOccupancy.Active,
                hasConnection = true,
            )
        assertEquals(LiveUpdatesLabel.Offline, chrome.label)
        assertFalse(chrome.canMutate)
    }

    @Test
    fun missingConnectionProfileIsOffline() {
        val chrome =
            composeLiveUpdates(
                connectionState = EventStreamConnectionState.Connecting,
                path = DeviceNetworkPathStatus.Ready,
                occupancy = StreamOccupancy.Active,
                hasConnection = false,
            )
        assertEquals(LiveUpdatesLabel.Offline, chrome.label)
        assertFalse(chrome.canMutate)
    }

    @Test
    fun authFailedBlocksMutations() {
        val chrome =
            composeLiveUpdates(
                connectionState = EventStreamConnectionState.AuthFailed,
                path = DeviceNetworkPathStatus.Ready,
                occupancy = StreamOccupancy.Active,
                hasConnection = true,
            )
        assertEquals(LiveUpdatesLabel.AuthFailed, chrome.label)
        assertEquals("Authentication failed", chrome.label.label)
        assertFalse(chrome.canMutate)
    }

    @Test
    fun connectingAllowsMutationsWhenPathIsReady() {
        val chrome =
            composeLiveUpdates(
                connectionState = EventStreamConnectionState.Connecting,
                path = DeviceNetworkPathStatus.Ready,
                occupancy = StreamOccupancy.Active,
                hasConnection = true,
            )
        assertEquals(LiveUpdatesLabel.Connecting, chrome.label)
        assertTrue(chrome.canMutate)
    }
}
