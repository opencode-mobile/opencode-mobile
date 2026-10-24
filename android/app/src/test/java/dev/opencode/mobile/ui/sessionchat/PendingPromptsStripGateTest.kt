package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.FollowUpDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPromptsStripGateTest {
    private val waiting = PendingPrompt(id = "msg-1", text = "Check the flash", delivery = FollowUpDelivery.Steer)
    private val settling =
        PendingPrompt(id = "pending-1", text = "Hold this", delivery = FollowUpDelivery.Queue, isSettling = true)

    @Test
    fun idleSendInboxBlip_neverMountsStrip() {
        val gate = PendingPromptsStripGate()

        val enqueued = gate.resolve(listOf(waiting), nowMs = 1_000)
        val delivered = gate.resolve(emptyList(), nowMs = 1_006)

        assertTrue(enqueued.prompts.isEmpty())
        assertEquals(PendingPromptsStripGate.GRACE_MS, enqueued.wakeAfterMs)
        assertEquals(PendingPromptsStripGate.Resolution(prompts = emptyList()), delivered)
    }

    @Test
    fun serverWaitingPrompt_mountsAfterGrace() {
        val gate = PendingPromptsStripGate()

        gate.resolve(listOf(waiting), nowMs = 1_000)
        val early = gate.resolve(listOf(waiting), nowMs = 1_200)
        val due = gate.resolve(listOf(waiting), nowMs = 1_300)

        assertTrue(early.prompts.isEmpty())
        assertEquals(100L, early.wakeAfterMs)
        assertEquals(PendingPromptsStripGate.Resolution(prompts = listOf(waiting)), due)
    }

    @Test
    fun ownBusySend_mountsAtOnce() {
        val gate = PendingPromptsStripGate()

        val resolution = gate.resolve(listOf(settling), nowMs = 1_000)

        assertEquals(PendingPromptsStripGate.Resolution(prompts = listOf(settling)), resolution)
    }

    @Test
    fun mountedStrip_staysThroughEchoHandoff() {
        val gate = PendingPromptsStripGate()

        gate.resolve(listOf(settling), nowMs = 1_000)
        val echoed = gate.resolve(listOf(waiting), nowMs = 1_010)

        assertEquals(PendingPromptsStripGate.Resolution(prompts = listOf(waiting)), echoed)
    }

    @Test
    fun emptyList_resetsGrace() {
        val gate = PendingPromptsStripGate()

        gate.resolve(listOf(waiting), nowMs = 1_000)
        gate.resolve(listOf(waiting), nowMs = 1_400)
        gate.resolve(emptyList(), nowMs = 1_500)
        val next = gate.resolve(listOf(waiting), nowMs = 2_000)

        assertTrue(next.prompts.isEmpty())
        assertEquals(PendingPromptsStripGate.GRACE_MS, next.wakeAfterMs)
    }
}
