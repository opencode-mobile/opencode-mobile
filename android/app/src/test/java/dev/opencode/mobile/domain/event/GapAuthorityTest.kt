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
}
