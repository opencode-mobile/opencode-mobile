package dev.opencode.mobile.domain.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOverallPolicyTest {
    @Test
    fun firstFullyLiveSet_isConnectedAndPublishesReconnect() {
        val decision =
            LiveOverallPolicy.decide(
                LiveOverallFlags(),
                LiveOverallInput(
                    required = listOf("global/event"),
                    live = setOf("global/event"),
                ),
            )
        assertEquals(EventStreamConnectionState.Connected, decision.connectionState)
        assertTrue(decision.publishReconnect)
        assertTrue(decision.flags.wasFullyConnected)
    }

    @Test
    fun stayingConnected_doesNotPublishReconnectAgain() {
        val first =
            LiveOverallPolicy.decide(
                LiveOverallFlags(),
                LiveOverallInput(
                    required = listOf("global/event"),
                    live = setOf("global/event"),
                ),
            )
        val second =
            LiveOverallPolicy.decide(
                first.flags,
                LiveOverallInput(
                    required = listOf("global/event"),
                    live = setOf("global/event"),
                ),
                currentlyConnected = true,
            )
        assertFalse(second.publishReconnect)
        assertEquals(EventStreamConnectionState.Connected, second.connectionState)
    }

    @Test
    fun occupancyResume_publishesReconnectOnFirstConnected() {
        val decision =
            LiveOverallPolicy.decide(
                LiveOverallFlags(wasFullyConnected = true),
                LiveOverallInput(
                    required = listOf("api/event"),
                    live = setOf("api/event"),
                ),
                currentlyConnected = false,
            )
        assertTrue(decision.publishReconnect)
        assertEquals(EventStreamConnectionState.Connected, decision.connectionState)
    }

    @Test
    fun requiredGrowth_goesConnectingThenReconnects() {
        val dropped =
            LiveOverallPolicy.decide(
                LiveOverallFlags(wasFullyConnected = true),
                LiveOverallInput(
                    required = listOf("global/event", "event:/tmp/a"),
                    live = setOf("global/event"),
                ),
                currentlyConnected = true,
            )
        assertEquals(EventStreamConnectionState.Reconnecting, dropped.connectionState)
        assertFalse(dropped.publishReconnect)
        val recovered =
            LiveOverallPolicy.decide(
                dropped.flags,
                LiveOverallInput(
                    required = listOf("global/event", "event:/tmp/a"),
                    live = setOf("global/event", "event:/tmp/a"),
                ),
                currentlyConnected = false,
            )
        assertEquals(EventStreamConnectionState.Connected, recovered.connectionState)
        assertTrue(recovered.publishReconnect)
    }

    @Test
    fun incumbentLeaseLost_isReconnectingWithoutReconnectEvent() {
        val decision =
            LiveOverallPolicy.decide(
                LiveOverallFlags(wasFullyConnected = true),
                LiveOverallInput(
                    required = listOf("global/event", "event:/tmp/a"),
                    live = setOf("global/event"),
                ),
                currentlyConnected = true,
            )
        assertEquals(EventStreamConnectionState.Reconnecting, decision.connectionState)
        assertFalse(decision.publishReconnect)
    }

    @Test
    fun notYetConnected_staysConnecting() {
        val decision =
            LiveOverallPolicy.decide(
                LiveOverallFlags(),
                LiveOverallInput(
                    required = listOf("api/event"),
                    live = emptySet(),
                ),
            )
        assertEquals(EventStreamConnectionState.Connecting, decision.connectionState)
        assertFalse(decision.publishReconnect)
    }
}
