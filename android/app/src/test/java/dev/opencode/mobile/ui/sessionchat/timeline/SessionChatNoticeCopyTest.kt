package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.NoticeModelRef
import dev.opencode.mobile.domain.message.TimelineNotice
import dev.opencode.mobile.domain.message.beginsMidTurn
import dev.opencode.mobile.domain.model.ModelSelectionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatNoticeCopyTest {
    private val names =
        SessionChatNoticeModelNames.from(
            listOf(
                ModelSelectionOption(
                    providerId = "anthropic",
                    providerName = "Anthropic",
                    modelId = "claude-opus-5-5",
                    modelName = "Claude Opus 5.5",
                ),
            ),
        )

    private fun line(notice: TimelineNotice) = SessionChatNoticeCopy.line(notice, names)

    @Test
    fun moved_showsTheGlyphAndTheFullDirectory() {
        val line = line(TimelineNotice.Moved("/repo/.worktrees/feat", previousDirectory = "/repo"))

        assertEquals(SessionChatNoticeLine(label = "Moved to", value = "/repo/.worktrees/feat", isMove = true), line)
        assertEquals("Moved to /repo/.worktrees/feat", line.accessibilityLabel)
    }

    @Test
    fun agentChanged_titleCasesAndArrowsOnlyWhenThePreviousAgentDiffers() {
        assertEquals(
            SessionChatNoticeLine(label = "Agent changed", value = "Build → Plan"),
            line(TimelineNotice.AgentChanged("plan", previous = "build")),
        )
        assertEquals(
            SessionChatNoticeLine(label = "Agent changed", value = "Build"),
            line(TimelineNotice.AgentChanged("build")),
        )
        assertEquals(
            SessionChatNoticeLine(label = "Agent changed", value = "Build"),
            line(TimelineNotice.AgentChanged("build", previous = "build")),
        )
    }

    @Test
    fun modelChanged_namesTheModelFromTheCatalogElseItsId() {
        assertEquals(
            SessionChatNoticeLine(label = "Switched to", value = "Claude Opus 5.5"),
            line(
                TimelineNotice.ModelChanged(
                    model = NoticeModelRef("anthropic", "claude-opus-5-5"),
                    previous = NoticeModelRef("openai", "gpt-5"),
                ),
            ),
        )
        assertEquals(
            SessionChatNoticeLine(label = "Switched to", value = "gpt-5"),
            line(TimelineNotice.ModelChanged(model = NoticeModelRef("openai", "gpt-5", variant = "high"))),
        )
    }

    @Test
    fun modelChanged_variantOnlyShowsTheVariantOrDefault() {
        val previous = NoticeModelRef("openai", "gpt-5", variant = "low")
        assertEquals(
            SessionChatNoticeLine(label = "Switched variant to", value = "high"),
            line(TimelineNotice.ModelChanged(NoticeModelRef("openai", "gpt-5", variant = "high"), previous)),
        )
        assertEquals(
            SessionChatNoticeLine(label = "Switched variant to Default"),
            line(TimelineNotice.ModelChanged(NoticeModelRef("openai", "gpt-5"), previous)),
        )
        assertEquals(
            SessionChatNoticeLine(label = "Switched variant to Default"),
            line(TimelineNotice.ModelChanged(NoticeModelRef("openai", "gpt-5", variant = "default"), previous)),
        )
    }

    @Test
    fun instructionsSystemAndRestart() {
        assertEquals(
            SessionChatNoticeLine(label = "Instructions updated", value = "AGENTS.md, CLAUDE.md"),
            line(TimelineNotice.InstructionsUpdated(listOf("AGENTS.md", "CLAUDE.md"))),
        )
        assertEquals(
            SessionChatNoticeLine(label = "Instructions updated"),
            line(TimelineNotice.InstructionsUpdated(emptyList())),
        )
        assertEquals(SessionChatNoticeLine(label = "Tools changed"), line(TimelineNotice.SystemNote("Tools changed")))
        val restart = line(TimelineNotice.Restart)
        assertEquals(SessionChatNoticeLine(label = "Continuing after restart"), restart)
        assertEquals("Continuing after restart", restart.accessibilityLabel)
    }

    @Test
    fun noticeRowsNeverCompleteAUserTurnOrStartAPage() {
        fun item(
            id: String,
            role: MessageRole,
        ) = SessionChatMessageItem(id = id, role = role, text = "", metadataLabel = "")
        val state =
            SessionChatUiState(
                sessionId = "ses",
                sessionTitle = "Session",
                timeline = SessionChatTimelineState.Ready,
                messages = listOf(item("u1", MessageRole.User), item("n1", MessageRole.Notice)),
            )
        assertNull(state.latestCompletedUserMessageId)
        assertEquals(
            "u1",
            state.copy(messages = state.messages + item("a1", MessageRole.Assistant)).latestCompletedUserMessageId,
        )

        val notice = Message(id = "n0", role = MessageRole.Notice, text = "")
        assertFalse(beginsMidTurn(listOf(notice, Message(id = "u1", role = MessageRole.User, text = "Go"))))
        assertTrue(beginsMidTurn(listOf(notice, Message(id = "a1", role = MessageRole.Assistant, text = "Hi"))))
    }
}
