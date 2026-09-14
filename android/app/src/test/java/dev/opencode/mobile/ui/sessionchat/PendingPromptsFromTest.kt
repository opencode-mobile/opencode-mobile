package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPromptsFromTest {
    @Test
    fun optimisticBusySend_isSettling() {
        val prompts =
            pendingPromptsFrom(
                loaded = emptyList(),
                optimistic = listOf(optimistic("pending-1", "Hold this", FollowUpDelivery.Steer)),
            )
        val prompt = prompts.single()
        assertEquals("pending-1", prompt.id)
        assertEquals("Hold this", prompt.text)
        assertEquals(FollowUpDelivery.Steer, prompt.delivery)
        assertTrue(prompt.isSettling)
    }

    @Test
    fun sameIdEcho_replacesSettlingRow() {
        val prompts =
            pendingPromptsFrom(
                loaded = listOf(inbox("pending-1", "Hold this", FollowUpDelivery.Steer)),
                optimistic = listOf(optimistic("pending-1", "Hold this", FollowUpDelivery.Steer)),
            )
        val prompt = prompts.single()
        assertEquals("pending-1", prompt.id)
        assertFalse(prompt.isSettling)
    }

    @Test
    fun differentIdEcho_usesMergeLeftoverNotContentDedupe() {
        // Production optimistic ids are pending-UUID; the echo has a server id.
        // mergeWithPending drops the optimistic row, so the caller passes empty
        // optimistic leftover. This function does not content-match across ids.
        val prompts =
            pendingPromptsFrom(
                loaded = listOf(inbox("msg-server", "Hold this", FollowUpDelivery.Queue)),
                optimistic = emptyList(),
            )
        val prompt = prompts.single()
        assertEquals("msg-server", prompt.id)
        assertEquals(FollowUpDelivery.Queue, prompt.delivery)
        assertFalse(prompt.isSettling)
    }

    @Test
    fun unionOrdersSteerBeforeQueue_serverThenSettling() {
        val prompts =
            pendingPromptsFrom(
                loaded =
                    listOf(
                        inbox("q-server", "Queued on server", FollowUpDelivery.Queue),
                        inbox("s-server", "Steer on server", FollowUpDelivery.Steer),
                    ),
                optimistic =
                    listOf(
                        optimistic("q-pending", "Queued settling", FollowUpDelivery.Queue),
                        optimistic("s-pending", "Steer settling", FollowUpDelivery.Steer),
                    ),
            )
        assertEquals(
            listOf("s-server", "s-pending", "q-server", "q-pending"),
            prompts.map { it.id },
        )
        assertEquals(
            listOf(false, true, false, true),
            prompts.map { it.isSettling },
        )
    }

    private fun inbox(
        id: String,
        text: String,
        delivery: FollowUpDelivery,
    ) = Message(
        id = id,
        role = MessageRole.User,
        text = text,
        followUpDelivery = delivery,
        isWaitingForPickup = true,
    )

    private fun optimistic(
        id: String,
        text: String,
        delivery: FollowUpDelivery,
    ) = SessionChatMessageItem(
        id = id,
        role = MessageRole.User,
        text = text,
        metadataLabel = "",
        pendingDelivery = delivery,
        isPending = true,
    )
}
