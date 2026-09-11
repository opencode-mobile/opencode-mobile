package dev.opencode.mobile.domain.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GapAuthorityTest {
    @Test
    fun lateSubscriber_seesOpenGap() {
        val authority = GapAuthority()
        val snapshot =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(3, LiveGapReason.Stopped),
                connectionState = EventStreamConnectionState.Connecting,
            )
        assertTrue(authority.needsReconcile(snapshot))
        assertEquals(3L, authority.tryBegin(snapshot))
    }

    @Test
    fun singleFlight_blocksDuplicateBegin() {
        val authority = GapAuthority()
        val snapshot =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(2, LiveGapReason.Reconnect),
                connectionState = EventStreamConnectionState.Connected,
            )
        assertEquals(2L, authority.tryBegin(snapshot))
        assertNull(authority.tryBegin(snapshot))
        assertFalse(authority.needsReconcile(snapshot))
    }

    @Test
    fun failedReload_remainsBehind() {
        val authority = GapAuthority()
        val snapshot =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(4, LiveGapReason.ParseFailed),
                connectionState = EventStreamConnectionState.Connected,
            )
        assertEquals(4L, authority.tryBegin(snapshot))
        authority.fail(4)
        assertTrue(authority.needsReconcile(snapshot))
        assertEquals(4L, authority.tryBegin(snapshot))
    }

    @Test
    fun acknowledge_clearsObligation() {
        val authority = GapAuthority()
        val snapshot =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(1, LiveGapReason.OccupancyStart),
                connectionState = EventStreamConnectionState.Connected,
            )
        authority.acknowledgeCurrent(snapshot)
        assertFalse(authority.needsReconcile(snapshot))
        assertNull(authority.tryBegin(snapshot))
    }

    @Test
    fun acknowledgeCurrent_whileOlderInFlight_pinsInFlightAndSuppressesReconcile() {
        val authority = GapAuthority()
        val begun =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(3, LiveGapReason.Reconnect),
                connectionState = EventStreamConnectionState.Connected,
            )
        assertEquals(3L, authority.tryBegin(begun))
        val newer =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(4, LiveGapReason.Reconnect),
                connectionState = EventStreamConnectionState.Connected,
            )
        authority.acknowledgeCurrent(newer)
        assertEquals(4L, authority.acknowledgedGeneration())
        assertEquals(3L, authority.inFlightGeneration())
        assertFalse(authority.needsReconcile(newer))
        assertNull(authority.tryBegin(newer))
    }

    @Test
    fun captureBegin_doesNotClobberExistingInFlight() {
        val authority = GapAuthority()
        val snapshot =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(5, LiveGapReason.Reconnect),
                connectionState = EventStreamConnectionState.Connected,
            )
        assertEquals(5L, authority.tryBegin(snapshot))
        assertEquals(5L, authority.captureBegin(snapshot))
        assertEquals(5L, authority.inFlightGeneration())
        authority.acknowledge(5)
        assertNull(authority.inFlightGeneration())
        assertFalse(authority.needsReconcile(snapshot))
    }

    @Test
    fun captureBegin_takesNewerObligation() {
        val authority = GapAuthority()
        val begun =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(5, LiveGapReason.Reconnect),
                connectionState = EventStreamConnectionState.Connected,
            )
        assertEquals(5L, authority.tryBegin(begun))
        val newer =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(6, LiveGapReason.Reconnect),
                connectionState = EventStreamConnectionState.Connected,
            )
        assertEquals(6L, authority.captureBegin(newer))
        assertEquals(6L, authority.inFlightGeneration())
        authority.acknowledge(5)
        assertEquals(5L, authority.acknowledgedGeneration())
        assertEquals(6L, authority.inFlightGeneration())
        assertFalse(authority.needsReconcile(newer))
        authority.fail(6)
        assertTrue(authority.needsReconcile(newer))
    }

    @Test
    fun acknowledge_exactInFlight_clearsAndLeavesNewerUnpaid() {
        val authority = GapAuthority()
        val begun =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(3, LiveGapReason.Reconnect),
                connectionState = EventStreamConnectionState.Connected,
            )
        assertEquals(3L, authority.tryBegin(begun))
        val newer =
            LiveConnectionSnapshot(
                gap = LiveGapRevision(4, LiveGapReason.Reconnect),
                connectionState = EventStreamConnectionState.Connected,
            )
        authority.acknowledge(3)
        assertEquals(3L, authority.acknowledgedGeneration())
        assertNull(authority.inFlightGeneration())
        assertTrue(authority.needsReconcile(newer))
        assertEquals(4L, authority.tryBegin(newer))
    }
}
