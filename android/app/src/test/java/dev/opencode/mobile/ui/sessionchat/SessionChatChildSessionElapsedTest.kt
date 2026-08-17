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
        val start = 1_710_000_000_000L
        assertEquals("0:00", SessionChatChildSessionElapsed.format(start, start))
        assertEquals("0:05", SessionChatChildSessionElapsed.format(start, start + 5_000))
        assertEquals("1:10", SessionChatChildSessionElapsed.format(start, start + 70_000))
        assertEquals("60:00", SessionChatChildSessionElapsed.format(start, start + 3_600_000))
    }

    @Test
    fun format_omitsRelativeOrInvalidStart() {
        assertNull(SessionChatChildSessionElapsed.format(10L, 1_710_000_000_000L))
        assertNull(SessionChatChildSessionElapsed.format(5_000L, 1_000L))
    }

    @Test
    fun format_clampsNegativeElapsedToZero() {
        val start = 1_710_000_000_000L
        assertEquals("0:00", SessionChatChildSessionElapsed.format(start, start - 500))
    }

    @Test
    fun letter_usesFirstCharacterUppercased() {
        assertEquals("E", SessionChatChildSessionElapsed.letter("explore"))
        assertEquals("T", SessionChatChildSessionElapsed.letter("Task"))
        assertEquals("?", SessionChatChildSessionElapsed.letter(""))
    }
}
