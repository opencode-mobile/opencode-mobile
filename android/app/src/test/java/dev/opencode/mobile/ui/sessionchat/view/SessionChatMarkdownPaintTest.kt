package dev.opencode.mobile.ui.sessionchat.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatMarkdownPaintTest {
    @Test
    fun shouldDefer_largeMarkdown_defers() {
        assertTrue(
            SessionChatMarkdownPaint.shouldDefer(
                textLength = SessionChatMarkdownPaint.DEFERRED_CHARACTER_THRESHOLD,
                hasMarkdown = true,
            ),
        )
    }

    @Test
    fun shouldDefer_smallMarkdown_paintsImmediately() {
        assertFalse(
            SessionChatMarkdownPaint.shouldDefer(
                textLength = SessionChatMarkdownPaint.DEFERRED_CHARACTER_THRESHOLD - 1,
                hasMarkdown = true,
            ),
        )
    }

    @Test
    fun shouldDefer_largePlain_doesNotDefer() {
        assertFalse(
            SessionChatMarkdownPaint.shouldDefer(
                textLength = SessionChatMarkdownPaint.DEFERRED_CHARACTER_THRESHOLD,
                hasMarkdown = false,
            ),
        )
    }
}
