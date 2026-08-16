package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionChatChildSessionElapsedTest {
    @Test
    fun format_omitsWhenStartMissing() {
        assertNull(SessionChatChildSessionElapsed.format(startEpochMs = null, nowEpochMs = 1_000))
    }

    @Test
    fun format_usesMinutesAndZeroPaddedSeconds() {
        val start = 1_000_000L
        assertEquals("0:00", SessionChatChildSessionElapsed.format(start, start))
        assertEquals("0:05", SessionChatChildSessionElapsed.format(start, start + 5_000))
        assertEquals("1:10", SessionChatChildSessionElapsed.format(start, start + 70_000))
        assertEquals("60:00", SessionChatChildSessionElapsed.format(start, start + 3_600_000))
    }

    @Test
    fun format_clampsNegativeElapsedToZero() {
        assertEquals("0:00", SessionChatChildSessionElapsed.format(5_000, 1_000))
    }

    @Test
    fun letter_usesFirstCharacterUppercased() {
        assertEquals("E", SessionChatChildSessionElapsed.letter("explore"))
        assertEquals("T", SessionChatChildSessionElapsed.letter("Task"))
        assertEquals("?", SessionChatChildSessionElapsed.letter(""))
    }
}
