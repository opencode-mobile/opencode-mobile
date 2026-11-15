package dev.opencode.mobile.ui.sessionchat.view

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageTextLimit
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTextLimit
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cut user message in the timeline (#1043): the shown part and its row, with the whole text
 * kept for Copy message, Select text and Show full message.
 */
class SessionChatMessageLimitPresentationTest {
    private val longText = (1..400).joinToString("\n") { "2026-01-01T00:00:00Z INFO worker $it ok" }

    private fun message(
        role: MessageRole,
        text: String = longText,
    ) = Message(id = "msg-1", role = role, text = text, sentAtEpochMs = 1)

    @Test
    fun longUserMessage_showsTheCutTextWithTheCounts() {
        val blocks = SessionChatTimelineBlocks.present(message(MessageRole.User), commands = emptyList())

        val cut = MessageTextLimit.cut(longText)!!
        val block = blocks.single() as SessionChatTimelineBlock.Text
        assertEquals(cut.visibleText, block.text)
        assertEquals(SessionChatTextLimit(shownCharacters = cut.shownCharacters, totalCharacters = longText.length), block.limit)
        assertNull(block.renderedMarkdown)
        // No narration for message-level markdown or the row's copy text: the row keeps the whole text.
        assertNull(block.markdownText)
    }

    @Test
    fun shortUserMessage_keepsItsText() {
        val blocks = SessionChatTimelineBlocks.present(message(MessageRole.User, "Run the tests"), commands = emptyList())

        val block = blocks.single() as SessionChatTimelineBlock.Text
        assertEquals("Run the tests", block.text)
        assertNull(block.limit)
    }

    @Test
    fun assistantMessageOfTheSameLength_isUntouched() {
        val blocks = SessionChatTimelineBlocks.present(message(MessageRole.Assistant), commands = emptyList())

        val block = blocks.single() as SessionChatTimelineBlock.Text
        assertEquals(longText, block.text)
        assertNull(block.limit)
    }

    @Test
    fun messageActionsAndFullMessage_getTheWholeText() {
        val item =
            SessionChatMessageItem(
                id = "msg-1",
                role = MessageRole.User,
                text = longText,
                metadataLabel = "",
                blocks = SessionChatTimelineBlocks.present(message(MessageRole.User), commands = emptyList()),
            )

        val actions = item.messageActionsTarget(showsRevertMessage = true)
        assertEquals(longText, actions.text)
        assertTrue(actions.showsRevertMessage)
        assertFalse(actions.isFullMessage)
        val full = item.fullMessageTarget()
        assertEquals(longText, full.text)
        assertTrue(full.isFullMessage)
    }
}
