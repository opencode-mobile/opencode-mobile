package dev.opencode.mobile.ui.sessionchat.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun codeBlockRange_isNullWithoutABlockOrAMatch() {
        assertNull(selectTextCodeBlockRange(message, null))
        assertNull(selectTextCodeBlockRange(message, "  \n"))
        assertNull(selectTextCodeBlockRange(message, "not in the message"))
    }
}
