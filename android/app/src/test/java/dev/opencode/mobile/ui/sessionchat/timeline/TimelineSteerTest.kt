package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steering in the timeline (#902): pending steers show as bubbles, consecutive steers
 * share one label, and delivery replaces the pending bubble in place.
 */
class TimelineSteerTest {
    @Test
    fun waitingSteerDuringRun_isPendingSteerWithLabel() {
        val frame = project(listOf(prompt("u1", "Start"), running("a1"), waiting("u2", "Use staging", FollowUpDelivery.Steer)))

        assertEquals(listOf("u1", "a1", "u2"), frame.rows.map { it.id })
        assertNull(frame.rows[0].steer)
        assertEquals(SessionChatSteer(isPending = true, showsLabel = true), frame.rows[2].steer)
    }

    @Test
    fun waitingQueuedPrompt_staysOutOfTimeline() {
        val frame = project(listOf(prompt("u1", "Start"), running("a1"), waiting("u2", "Later", FollowUpDelivery.Queue)))

        assertEquals(listOf("u1", "a1"), frame.rows.map { it.id })
    }

    @Test
    fun steerAndQueueTogether_onlySteerShows() {
        val frame =
            project(
                listOf(
                    prompt("u1", "Start"),
                    running("a1"),
                    waiting("u2", "Steer this", FollowUpDelivery.Steer),
                    waiting("u3", "Queue this", FollowUpDelivery.Queue),
                ),
            )

        assertEquals(listOf("u1", "a1", "u2"), frame.rows.map { it.id })
        assertEquals(true, frame.rows.last().steer?.isPending)
    }

    @Test
    fun consecutiveSteers_shareOneLabelOnTheFirst() {
        val frame =
            project(
                listOf(
                    prompt("u1", "Start"),
                    running("a1"),
                    waiting("u2", "First", FollowUpDelivery.Steer),
                    waiting("u3", "Second", FollowUpDelivery.Steer),
                ),
            )

        assertEquals(SessionChatSteer(isPending = true, showsLabel = true), frame.rows[2].steer)
        assertEquals(SessionChatSteer(isPending = true, showsLabel = false), frame.rows[3].steer)
    }

    @Test
    fun noticeBetweenSteers_doesNotSplitTheGroup() {
        fun row(
            id: String,
            role: MessageRole,
            delivery: FollowUpDelivery? = null,
        ) = TimelineRow(
            id = id,
            role = role,
            text = id,
            metadataLabel = "",
            pendingDelivery = delivery,
            contentRevision = ContentRevision.of(id),
            layoutRevision = 0L,
        )
        val marked =
            TimelineProjector.markSteers(
                rows =
                    listOf(
                        row("u2", MessageRole.User, FollowUpDelivery.Steer),
                        row("n1", MessageRole.Notice),
                        row("u3", MessageRole.User, FollowUpDelivery.Steer),
                    ),
                previousById = emptyMap(),
                listIdAliases = emptyMap(),
                runIsActive = true,
            )

        assertEquals(
            listOf(
                SessionChatSteer(isPending = true, showsLabel = true),
                null,
                SessionChatSteer(isPending = true, showsLabel = false),
            ),
            marked.map { it.steer },
        )
    }

    @Test
    fun steersSplitByAssistantOutput_eachGetALabel() {
        val first = project(listOf(prompt("u1", "Start"), running("a1"), waiting("u2", "First", FollowUpDelivery.Steer)))
        val next =
            project(
                listOf(
                    prompt("u1", "Start"),
                    done("a1"),
                    prompt("u2", "First"),
                    running("a2"),
                    waiting("u3", "Second", FollowUpDelivery.Steer),
                ),
                previous = first,
            )

        assertEquals(SessionChatSteer(isPending = false, showsLabel = true), next.rows.first { it.id == "u2" }.steer)
        assertEquals(SessionChatSteer(isPending = true, showsLabel = true), next.rows.first { it.id == "u3" }.steer)
    }

    @Test
    fun delivery_replacesPendingBubbleInPlaceAndKeepsLabel() {
        val pending = project(listOf(prompt("u1", "Start"), running("a1"), waiting("u2", "Use staging", FollowUpDelivery.Steer)))
        // The server's user message: same id, no longer waiting (SSE or REST refresh).
        val delivered =
            project(
                listOf(prompt("u1", "Start"), done("a1"), prompt("u2", "Use staging"), running("a2")),
                previous = pending,
            )

        assertEquals(listOf("u1", "a1", "u2", "a2"), delivered.rows.map { it.id })
        assertEquals(1, delivered.rows.count { it.id == "u2" })
        assertEquals(SessionChatSteer(isPending = false, showsLabel = true), delivered.rows[2].steer)
        assertFalse("u2" in delivered.insertedRowIDs)
        assertFalse("u2" in delivered.removedRowIDs)
        assertTrue("u2" in delivered.changedRowIDs)
    }

    @Test
    fun deliveredSteer_keepsLabelAfterTurnEnds() {
        val pending = project(listOf(prompt("u1", "Start"), running("a1"), waiting("u2", "Use staging", FollowUpDelivery.Steer)))
        val delivered =
            project(listOf(prompt("u1", "Start"), done("a1"), prompt("u2", "Use staging"), running("a2")), previous = pending)
        val idle =
            project(listOf(prompt("u1", "Start"), done("a1"), prompt("u2", "Use staging"), done("a2")), previous = delivered)

        assertEquals(SessionChatSteer(isPending = false, showsLabel = true), idle.rows[2].steer)
    }

    @Test
    fun idleSendFromAnotherClient_isNotLabelled() {
        // v2 enqueues an idle prompt as a steer; the finished turn means no run to steer.
        val waitingFrame = project(listOf(prompt("u1", "Start"), done("a1"), waiting("u2", "Next", FollowUpDelivery.Steer)))
        val delivered =
            project(listOf(prompt("u1", "Start"), done("a1"), prompt("u2", "Next"), running("a2")), previous = waitingFrame)

        assertNull(waitingFrame.rows.last().steer)
        assertNull(delivered.rows[2].steer)
    }

    @Test
    fun optimisticBusySteer_handsOffToServerRowWithoutDuplicate() {
        val optimisticRow = optimisticRow(CLIENT_ID, "Use staging", FollowUpDelivery.Steer)
        val optimistic = OptimisticInputs(pendingRows = listOf(optimisticRow), baselineIds = setOf("u1", "a1"))
        val base = listOf(prompt("u1", "Start"), running("a1"))

        val sent = project(base, optimistic = optimistic)
        assertEquals(listOf("u1", "a1", CLIENT_ID), sent.rows.map { it.id })
        assertEquals(SessionChatSteer(isPending = true, showsLabel = true), sent.rows.last().steer)

        val echoed = project(base + waiting(CLIENT_ID, "Use staging", FollowUpDelivery.Steer), optimistic = optimistic, previous = sent)
        assertEquals(listOf("u1", "a1", CLIENT_ID), echoed.rows.map { it.id })
        assertEquals(SessionChatSteer(isPending = true, showsLabel = true), echoed.rows.last().steer)

        val delivered =
            project(
                listOf(prompt("u1", "Start"), done("a1"), prompt(CLIENT_ID, "Use staging"), running("a2")),
                optimistic = optimistic,
                previous = echoed,
            )
        assertEquals(listOf("u1", "a1", CLIENT_ID, "a2"), delivered.rows.map { it.id })
        assertEquals(SessionChatSteer(isPending = false, showsLabel = true), delivered.rows[2].steer)
    }

    @Test
    fun optimisticIdleSend_waitingSteerEchoDoesNotDuplicate() {
        val optimistic = OptimisticInputs(pendingRows = listOf(optimisticRow(CLIENT_ID, "Hello", null)))

        val echoed = project(listOf(waiting(CLIENT_ID, "Hello", FollowUpDelivery.Steer)), optimistic = optimistic)

        assertEquals(listOf(CLIENT_ID), echoed.rows.map { it.id })
        assertTrue(echoed.rows.single().isPending)
        assertNull(echoed.rows.single().steer)
    }

    @Test
    fun optimisticQueuedSend_staysOutOfTimeline() {
        val queued = SessionChatMessageItem("q", MessageRole.User, "Later", "", FollowUpDelivery.Queue, isPending = true)
        val steer = SessionChatMessageItem("s", MessageRole.User, "Now", "", FollowUpDelivery.Steer, isPending = true)

        assertEquals(listOf("s"), TimelineProjector.displayedOptimisticSendItems(listOf(queued, steer)).map { it.id })
    }

    @Test
    fun optimisticSteerInsert_isPendingSteerBeforeAnyEcho() {
        val running = project(listOf(prompt("u1", "Start"), running("a1"), waiting("u2", "First", FollowUpDelivery.Steer)))
        val steer = SessionChatMessageItem("s", MessageRole.User, "Second", "", FollowUpDelivery.Steer, isPending = true)
        val idle = SessionChatMessageItem("i", MessageRole.User, "Hello", "", isPending = true)

        assertEquals(
            SessionChatSteer(isPending = true, showsLabel = false),
            TimelineProjector.markOptimisticSteers(listOf(steer), after = running).single().steer,
        )
        assertNull(TimelineProjector.markOptimisticSteers(listOf(idle), after = running).single().steer)
        assertEquals(
            SessionChatSteer(isPending = true, showsLabel = true),
            TimelineProjector.markOptimisticSteers(listOf(steer), after = null).single().steer,
        )
    }

    @Test
    fun steerMark_survivesConversionToMessageItem() {
        val frame = project(listOf(prompt("u1", "Start"), running("a1"), waiting("u2", "Use staging", FollowUpDelivery.Steer)))
        val item = TimelineProjector.toMessageItem(frame.rows[2])

        assertEquals(SessionChatSteer(isPending = true, showsLabel = true), item.steer)
        assertEquals(item.steer, TimelineProjector.rowFromMessageItem(item).steer)
    }

    private fun project(
        messages: List<Message>,
        optimistic: OptimisticInputs = OptimisticInputs(),
        previous: TimelineFrame? = null,
    ): TimelineFrame {
        val revision = (previous?.frameRevision ?: 0L) + 1L
        return TimelineProjector.project(
            frame =
                DisplayFrame(
                    sessionKey = "session",
                    frameRevision = revision,
                    sourceRevision = revision,
                    messages = messages.map { DisplayMessageBody(it, blocksFor(it)) },
                ),
            optimisticInputs = optimistic,
            previous = previous,
        )
    }

    private fun blocksFor(message: Message): List<SessionChatTimelineBlock> =
        when (val result = SessionChatTimelineBlocks.build(message.role, message.parts)) {
            is SessionChatTimelineBlocksResult.Ok -> result.blocks
            SessionChatTimelineBlocksResult.MissingPartIds -> error("expected Ok")
        }

    private fun prompt(
        id: String,
        text: String,
    ): Message = Message(id = id, role = MessageRole.User, text = text, parts = listOf(MessagePart.Text(text, id = "$id:text")))

    private fun waiting(
        id: String,
        text: String,
        delivery: FollowUpDelivery,
    ): Message = prompt(id, text).copy(isWaitingForPickup = true, followUpDelivery = delivery)

    private fun running(id: String): Message = assistant(id, completedAtEpochMs = null)

    private fun done(id: String): Message = assistant(id, completedAtEpochMs = 2L)

    private fun assistant(
        id: String,
        completedAtEpochMs: Long?,
    ): Message =
        Message(
            id = id,
            role = MessageRole.Assistant,
            text = "Working",
            parts = listOf(MessagePart.Text("Working", id = "$id:text")),
            completedAtEpochMs = completedAtEpochMs,
        )

    private fun optimisticRow(
        id: String,
        text: String,
        delivery: FollowUpDelivery?,
    ): TimelineRow =
        TimelineProjector.rowFromMessageItem(
            SessionChatMessageItem(
                id = id,
                role = MessageRole.User,
                text = text,
                metadataLabel = "",
                pendingDelivery = delivery,
                isPending = true,
                blocks = listOf(SessionChatTimelineBlock.Text(blockID = PresentationBlockId.direct(id), text = text)),
            ),
        )

    private companion object {
        const val CLIENT_ID = "msg_19a1b2c3d4e5AbCdEfGhIjKlMn"
    }
}
