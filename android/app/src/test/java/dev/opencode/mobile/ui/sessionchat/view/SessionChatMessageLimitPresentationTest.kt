package dev.opencode.mobile.ui.sessionchat.view

import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageTextLimit
import dev.opencode.mobile.markdown.MarkdownBlock
import dev.opencode.mobile.markdown.MarkdownInline
import dev.opencode.mobile.markdown.OpenCodeMarkdownParser
import dev.opencode.mobile.markdown.OpenCodeStructureOnlyHighlighter
import dev.opencode.mobile.markdown.stableMarkdownSourceHash
import dev.opencode.mobile.ui.sessionchat.ContentRevision
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTextLimit
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    private val markdown = "# Title\n\n" + (1..60).joinToString("\n") { "- item $it" }

    private fun parse(text: String) = OpenCodeMarkdownParser.parse(markdown = text, codeHighlighter = OpenCodeStructureOnlyHighlighter)

    @Test
    fun cutMarkdownMessage_rendersMarkdownBlocksUpToTheCut() {
        val shown = MessageTextLimit.cut(markdown)!!.visibleText
        val document = parse(shown)

        val blocks =
            SessionChatTimelineBlocks.attachLimitedTextMarkdown(
                blocks = SessionChatTimelineBlocks.present(message(MessageRole.User, markdown), commands = emptyList()),
                document = document,
                messageText = markdown,
            )

        val block = blocks.single() as SessionChatTimelineBlock.Text
        assertEquals(shown, block.text)
        assertNotNull(block.limit)
        assertSame(document, block.renderedMarkdown)
        assertTrue(document.blocks.first() is MarkdownBlock.Heading)
        assertTrue(document.blocks.last() is MarkdownBlock.BulletList)
    }

    @Test
    fun shownPartDocument_neverLandsOnAnUncutText() {
        // A long command template collapses to a short `[/review] args`: the message text is
        // cut, its bubble is not, so the document of the cut part must not reach the bubble.
        val template = (1..60).joinToString("\n") { "- check $it" }
        val text = "$template\n\nthe parser"
        val document = parse(MessageTextLimit.cut(text)!!.visibleText)
        val presented =
            SessionChatTimelineBlocks.present(
                message(MessageRole.User, text),
                commands = listOf(ServerCommand(name = "review", template = template)),
            )

        val blocks = SessionChatTimelineBlocks.attachLimitedTextMarkdown(presented, document, messageText = text)

        val block = blocks.single() as SessionChatTimelineBlock.Text
        assertEquals("[/review] the parser", block.text)
        assertNull(block.limit)
        assertNull(block.renderedMarkdown)
    }

    @Test
    fun cutInsideACodeFence_rendersACodeBlockToTheCut() {
        val text =
            "Look at this:\n\n```kotlin\n" + (1..60).joinToString("\n") { "val value$it = $it" } + "\n```\n\nDone"
        val shown = MessageTextLimit.cut(text)!!.visibleText

        val code = parse(shown).blocks.last() as MarkdownBlock.CodeBlock

        assertTrue(code.code.contains("val value1 = 1"))
        assertFalse(code.code.contains("val value60 = 60"))
        assertFalse(shown.contains("Done"))
    }

    @Test
    fun midLineCut_keepsItsMarkerInTheLastListItem() {
        val text = (1..20).joinToString("\n") { "- item $it" } + "\n- " + (1..200).joinToString(" ") { "word$it" }
        val shown = MessageTextLimit.cut(text)!!.visibleText
        assertTrue(shown.endsWith("…"))

        val blocks = parse(shown).blocks

        val list = blocks.single() as MarkdownBlock.BulletList
        assertEquals(21, list.items.size)
        assertTrue((list.items.last().inlines.last() as MarkdownInline.Text).text.endsWith("…"))
    }

    @Test
    fun multiMegabyteUserMessage_isRevisedFromItsShownPart() {
        val text = (1..60_000).joinToString("\n") { "2026-01-01T00:00:00Z INFO worker $it ok" }
        val blocks = SessionChatTimelineBlocks.present(message(MessageRole.User, text), commands = emptyList())
        val shown = (blocks.single() as SessionChatTimelineBlock.Text).text

        val revision = ContentRevision.of(text, blocks)

        assertEquals(ContentRevision(textHash = stableMarkdownSourceHash(shown), textLength = text.length), revision)
        // The optimistic row and the server echo of the same text get the same revision.
        val echo = SessionChatTimelineBlocks.present(message(MessageRole.User, text), commands = emptyList())
        assertEquals(revision, ContentRevision.of(text, echo))
    }
}
