package dev.opencode.mobile.domain.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOverallPolicyTest {
    @Test
    fun firstFullyLiveSet_isConnectedWithoutReconnect() {
        val decision =
            LiveOverallPolicy.decide(
                LiveOverallFlags(),
                LiveOverallInput(
                    required = listOf("global/event"),
                    live = setOf("global/event"),
                    pathLost = false,
                ),
            )
        assertEquals(EventStreamConnectionState.Connected, decision.connectionState)
        assertFalse(decision.publishReconnect)
        assertNull(decision.openGap)
        assertTrue(decision.flags.wasFullyConnected)
        assertTrue(decision.flags.occupyingConnected)
        assertTrue(decision.flags.lastAllLive)
    }

    @Test
    fun allLiveAfterFullConnection_publishesReconnectOnce() {
        val first =
            LiveOverallPolicy.decide(
                LiveOverallFlags(wasFullyConnected = true),
                LiveOverallInput(
                    required = listOf("global/event"),
                    live = setOf("global/event"),
                    pathLost = false,
                ),
            )
        assertTrue(first.publishReconnect)
        assertTrue(first.flags.emittedReconnectForCurrentGap)
        val second =
            LiveOverallPolicy.decide(
                first.flags,
                LiveOverallInput(
                    required = listOf("global/event"),
                    live = setOf("global/event"),
                    pathLost = false,
                ),
            )
        assertFalse(second.publishReconnect)
    }

    @Test
    fun coldResumeBarrier_publishesReconnectEvenIfNeverFullyConnected() {
        val decision =
            LiveOverallPolicy.decide(
                LiveOverallFlags(pendingResumeBarrier = true),
                LiveOverallInput(
                    required = listOf("api/event"),
                    live = setOf("api/event"),
                    pathLost = false,
                ),
            )
        assertTrue(decision.publishReconnect)
        assertFalse(decision.flags.pendingResumeBarrier)
    }

    @Test
    fun requiredGrowth_doesNotOpenGapOrReconnect() {
        val dropped =
            LiveOverallPolicy.decide(
                LiveOverallFlags(
                    lastAllLive = true,
                    lastRequiredTargets = listOf("global/event"),
                    lastLiveTargets = setOf("global/event"),
                    wasFullyConnected = true,
                ),
                LiveOverallInput(
                    required = listOf("global/event", "event:/tmp/a"),
                    live = setOf("global/event"),
                    pathLost = false,
                ),
            )
        assertEquals(EventStreamConnectionState.Reconnecting, dropped.connectionState)
        assertNull(dropped.openGap)
        assertTrue(dropped.flags.suppressReconnectForRequiredGrowth)
        val recovered =
            LiveOverallPolicy.decide(
                dropped.flags,
                LiveOverallInput(
                    required = listOf("global/event", "event:/tmp/a"),
                    live = setOf("global/event", "event:/tmp/a"),
                    pathLost = false,
                ),
            )
        assertEquals(EventStreamConnectionState.Connected, recovered.connectionState)
        assertFalse(recovered.publishReconnect)
        assertFalse(recovered.flags.suppressReconnectForRequiredGrowth)
    }

    @Test
    fun incumbentLeaseLost_opensLeaseLostGap() {
        val decision =
            LiveOverallPolicy.decide(
                LiveOverallFlags(
                    lastAllLive = true,
                    lastRequiredTargets = listOf("global/event", "event:/tmp/a"),
                    lastLiveTargets = setOf("global/event", "event:/tmp/a"),
                    wasFullyConnected = true,
                ),
                LiveOverallInput(
                    required = listOf("global/event", "event:/tmp/a"),
                    live = setOf("global/event"),
                    pathLost = false,
                ),
            )
        assertEquals(LiveGapReason.LeaseLost, decision.openGap)
        assertEquals(EventStreamConnectionState.Reconnecting, decision.connectionState)
        assertFalse(decision.publishReconnect)
    }

    @Test
    fun incumbentLostWhilePathUnavailable_opensPathLostGap() {
        val decision =
            LiveOverallPolicy.decide(
                LiveOverallFlags(
                    lastRequiredTargets = listOf("api/event"),
                    lastLiveTargets = setOf("api/event"),
                    wasFullyConnected = true,
                ),
                LiveOverallInput(
                    required = listOf("api/event"),
                    live = emptySet(),
                    pathLost = true,
                ),
            )
        assertEquals(LiveGapReason.PathLost, decision.openGap)
    }

    @Test
    fun notYetConnected_staysConnecting() {
        val decision =
            LiveOverallPolicy.decide(
                LiveOverallFlags(),
                LiveOverallInput(
                    required = listOf("api/event"),
                    live = emptySet(),
                    pathLost = false,
                ),
            )
        assertEquals(EventStreamConnectionState.Connecting, decision.connectionState)
        assertNull(decision.openGap)
        assertFalse(decision.publishReconnect)
    }
}
