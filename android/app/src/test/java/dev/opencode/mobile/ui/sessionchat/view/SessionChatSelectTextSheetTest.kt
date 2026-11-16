package dev.opencode.mobile.ui.sessionchat.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatSelectTextSheetTest {
    private val message = "Run this:\n\n```kotlin\nval a = 1\nval b = 2\n```\n\nDone."

    @Test
    fun codeBlockRange_findsTheBlockInTheRawText() {
        val code = "val a = 1\nval b = 2"

        val range = selectTextCodeBlockRange(message, code)

        assertEquals(code, message.substring(range!!.first, range.last + 1))
    }

    @Test
    fun codeBlockRange_ignoresSurroundingWhitespaceOfTheRenderedBlock() {
        val range = selectTextCodeBlockRange(message, "\nval a = 1\nval b = 2\n\n")

        assertEquals(message.indexOf("val a"), range!!.first)
    }

    @Test
    fun codeBlockRange_fallsBackToTheFirstLine() {
        val range = selectTextCodeBlockRange(message, "val a = 1\nval b   =   2")

        assertEquals("val a = 1", message.substring(range!!.first, range.last + 1))
    }

    @Test
    fun scrollsItself_onlyForVeryLongText() {
        assertFalse(selectTextScrollsItself(message))
        assertTrue(selectTextScrollsItself("x\n".repeat(20_000)))
    }

    @Test
    fun chunks_stackAsTheTextsLines() {
        val text = (1..100).joinToString("\n") { "line $it" }

        val chunks = selectTextChunks(text)

        assertEquals(3, chunks.size)
        assertEquals(text, chunks.joinToString("\n") { text.substring(it.first, it.last + 1) })
        assertEquals((1..SELECT_TEXT_CHUNK_LINES).joinToString("\n") { "line $it" }, text.substring(chunks[0].first, chunks[0].last + 1))
    }

    @Test
    fun chunks_endBeforeACrlf() {
        val text = (1..100).joinToString("\r\n") { "line $it" }

        val chunks = selectTextChunks(text)

        assertEquals(3, chunks.size)
        assertTrue(chunks.none { text[it.last] == '\r' })
        assertEquals(text, chunks.joinToString("\r\n") { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun chunks_splitALongLineWithoutSplittingASurrogatePair() {
        val text = "a".repeat(SELECT_TEXT_CHUNK_CHARACTERS - 1) + "😀" + "b".repeat(10)

        val chunks = selectTextChunks(text)

        assertEquals(text, chunks.joinToString("") { text.substring(it.first, it.last + 1) })
        assertEquals(SELECT_TEXT_CHUNK_CHARACTERS + 1, chunks[0].last + 1)
        assertTrue(chunks.all { it.last - it.first + 1 <= SELECT_TEXT_CHUNK_CHARACTERS + 1 })
    }

    @Test
    fun chunks_ofAnEmptyText_isOneEmptyPiece() {
        assertEquals(listOf(0 until 0), selectTextChunks(""))
    }

    @Test
    fun codeBlockRange_isNullWithoutABlockOrAMatch() {
        assertNull(selectTextCodeBlockRange(message, null))
        assertNull(selectTextCodeBlockRange(message, "  \n"))
        assertNull(selectTextCodeBlockRange(message, "not in the message"))
    }
}
