package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusAttentionMark
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusPillPhase
import dev.opencode.mobile.ui.sessionchat.view.SessionChatActivityPillCopy
import dev.opencode.mobile.ui.text.EnglishStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SessionChatLiveThoughtTest {
    private fun user(
        id: String = "user-1",
        waiting: Boolean = false,
    ) = Message(id = id, role = MessageRole.User, text = "Go", isWaitingForPickup = waiting)

    private fun assistant(
        vararg parts: MessagePart,
        id: String = "assistant-1",
    ) = Message(id = id, role = MessageRole.Assistant, text = "", parts = parts.toList())

    private fun thought(
        id: String,
        text: String = "Checking the retry clock.",
        streaming: Boolean = true,
        start: Long? = null,
        end: Long? = null,
    ) = MessagePart.Reasoning(text = text, isStreaming = streaming, id = id, timeStartEpochMs = start, timeEndEpochMs = end)

    // Which thought is live

    @Test
    fun streamingThoughtAtTheEndOfTheTurnIsLive() {
        val messages = listOf(user(), assistant(MessagePart.Text(text = "Looking.", id = "t1"), thought("r1")))
        assertEquals("r1", SessionChatLiveThought.streamingPartId(messages))
    }

    @Test
    fun settledThoughtIsNotLive() {
        assertNull(SessionChatLiveThought.streamingPartId(listOf(user(), assistant(thought("r1", streaming = false)))))
    }

    @Test
    fun aToolOrTextAfterTheThoughtEndsIt() {
        val tool = MessagePart.Tool(toolName = "grep", status = ToolStatus.Running, id = "tool-1")
        assertNull(SessionChatLiveThought.streamingPartId(listOf(user(), assistant(thought("r1"), tool))))
        val text = MessagePart.Text(text = "", isStreaming = true, id = "t1")
        assertNull(SessionChatLiveThought.streamingPartId(listOf(user(), assistant(thought("r1"), text))))
    }

    @Test
    fun hiddenPartsAfterTheThoughtDoNotEndIt() {
        val todo = MessagePart.Tool(toolName = "todowrite", status = ToolStatus.Running, id = "tool-1")
        val synthetic = MessagePart.Text(text = "context", id = "t1", isSynthetic = true)
        assertEquals("r1", SessionChatLiveThought.streamingPartId(listOf(user(), assistant(thought("r1"), todo, synthetic))))
    }

    @Test
    fun blankOrRedactedThoughtIsNotLive() {
        assertNull(SessionChatLiveThought.streamingPartId(listOf(user(), assistant(thought("r1", text = "")))))
        assertNull(SessionChatLiveThought.streamingPartId(listOf(user(), assistant(thought("r1", text = "[REDACTED]")))))
    }

    @Test
    fun onlyTheCurrentTurnCounts() {
        val stale = listOf(user("user-1"), assistant(thought("r1")), user("user-2"))
        assertNull(SessionChatLiveThought.streamingPartId(stale))
        val queuedPrompt = listOf(user("user-1"), assistant(thought("r1")), user("user-2", waiting = true))
        assertEquals("r1", SessionChatLiveThought.streamingPartId(queuedPrompt))
    }

    // What the sheet shows

    @Test
    fun sheetFollowsTheThoughtWhileItStreamsAndSettles() {
        val live = listOf(user(), assistant(thought("r1", text = "First [REDACTED] step", start = 1_000)))
        assertEquals(
            SessionChatThoughtSheetState(partId = "r1", text = "First  step", isStreaming = true, durationMs = null),
            SessionChatLiveThought.sheet("r1", live),
        )
        val settled =
            listOf(user(), assistant(thought("r1", text = "First step, then the fix.", streaming = false, start = 1_000, end = 2_500)))
        assertEquals(
            SessionChatThoughtSheetState(partId = "r1", text = "First step, then the fix.", isStreaming = false, durationMs = 1_500),
            SessionChatLiveThought.sheet("r1", settled),
        )
    }

    @Test
    fun sheetKeepsAnEarlierThoughtAfterTheTurnMovesOn() {
        val messages = listOf(user(), assistant(thought("r1", text = "Earlier", streaming = false), thought("r2", text = "Later")))
        assertEquals("Earlier", SessionChatLiveThought.sheet("r1", messages)?.text)
    }

    @Test
    fun sheetClosesWhenTheThoughtIsGone() {
        assertNull(SessionChatLiveThought.sheet("r1", listOf(user())))
        assertNull(SessionChatLiveThought.sheet("r1", listOf(user(), assistant(thought("r1", text = "[REDACTED]")))))
    }

    @Test
    fun sheetSublineSaysThinkingUntilTheThoughtSettles() {
        val live = SessionChatThoughtSheetState(partId = "r1", text = "Checking.", isStreaming = true, durationMs = null)
        assertEquals("Thinking…", live.detailTarget("Thinking…", Locale.US).presentation?.thoughtDuration())
        val settled = SessionChatThoughtSheetState(partId = "r1", text = "Checking.", isStreaming = false, durationMs = 1_500)
        assertEquals("1.5s", settled.detailTarget("Thinking…", Locale.US).presentation?.thoughtDuration())
        assertEquals("Checking.", settled.detailTarget("Thinking…", Locale.US).body)
    }

    private fun ToolResultPresentation.thoughtDuration(): String? = (this as? ToolResultPresentation.Thought)?.durationLabel

    // Turn pill

    private fun slot(
        label: UiText? = SessionChatStatusText.working,
        fading: Boolean = false,
        awaiting: Boolean = false,
        items: List<SessionChatActivityItem> = emptyList(),
        keyboard: Boolean = false,
        thinking: Boolean = true,
    ) = SessionChatPillSlots.turnStatus(label, fading, awaiting, items, keyboard, false, EnglishStrings, thinking)

    @Test
    fun workingPillReadsThinkingAndOpensTheThought() {
        val pill = slot()
        assertEquals(OpenCodeTurnStatusPillPhase.Working("Thinking"), pill?.phase)
        assertEquals(SessionChatActivityItem.Thinking, pill?.item)
        assertEquals(
            "Thinking. Show thought",
            SessionChatActivityPillCopy.accessibilityLabel(SessionChatActivityItem.Thinking).resolve(EnglishStrings),
        )
        assertEquals(
            SessionChatTurnStatusSlot(OpenCodeTurnStatusPillPhase.Working("Working"), compact = false, leaving = false),
            slot(thinking = false),
        )
    }

    @Test
    fun thinkingPillStillFoldsWithTheKeyboard() {
        assertEquals(true, slot(keyboard = true)?.compact)
    }

    @Test
    fun otherTurnLabelsAndRequestsWinOverThinking() {
        assertEquals(OpenCodeTurnStatusPillPhase.Working("Compacting…"), slot(label = SessionCompactChrome.statusLabel)?.phase)
        assertNull(slot(label = SessionCompactChrome.statusLabel)?.item)
        assertEquals(OpenCodeTurnStatusPillPhase.Done, slot(label = WorkingTurnIndicator.idleLabel)?.phase)
        assertNull(slot(label = null))
        assertEquals(
            OpenCodeTurnStatusPillPhase.Attention("Waiting for you", OpenCodeTurnStatusAttentionMark.PulsingDot),
            slot(awaiting = true)?.phase,
        )
        assertEquals(SessionChatActivityItem.ReviewPermission, slot(items = listOf(SessionChatActivityItem.ReviewPermission))?.item)
    }

    @Test
    fun fadingPillKeepsItsLeavingWorkingLabel() {
        val pill = slot(fading = true)
        assertEquals(OpenCodeTurnStatusPillPhase.Working("Working"), pill?.phase)
        assertTrue(pill?.leaving == true)
        assertNull(pill?.item)
    }

    @Test
    fun thinkingIsNeverASecondaryPill() {
        assertEquals(
            listOf(SessionChatActivityItem.Queued(1)),
            SessionChatPillSlots.secondaryItems(listOf(SessionChatActivityItem.Thinking, SessionChatActivityItem.Queued(1))),
        )
    }
}
