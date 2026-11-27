package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionChatStatusTextTest {
    /** Working and Retrying are app text; only the server's retry reason stays verbatim. */
    @Test
    fun label_keysAppTextAndKeepsTheRetryReasonVerbatim() {
        assertNull(SessionChatStatusText.label(SessionActivity.Idle))
        assertEquals(UiText.resource(R.string.component_activity_card_working), SessionChatStatusText.label(SessionActivity.Running))
        assertEquals("Working", SessionChatStatusText.label(SessionActivity.Running)?.english())
        assertEquals("Retrying", SessionChatStatusText.label(SessionActivity.Retrying())?.english())
        assertEquals("Retrying · 2", SessionChatStatusText.label(SessionActivity.Retrying(attempt = 2, message = "  "))?.english())
        assertEquals(
            UiText.resource(
                R.string.timeline_list_separator,
                UiText.resource(R.string.session_list_status_retrying_attempt, 2),
                UiText.verbatim("backoff"),
            ),
            SessionChatStatusText.label(SessionActivity.Retrying(attempt = 2, message = " backoff ")),
        )
        assertEquals("Retrying · 2 · backoff", SessionChatStatusText.label(SessionActivity.Retrying(attempt = 2, message = "backoff"))?.english())
    }

    @Test
    fun sending_isAnEllipsisThenAPercent() {
        assertEquals("Sending…", SessionChatStatusText.sending(0).english())
        assertEquals("Sending 25%", SessionChatStatusText.sending(25).english())
    }

    /** A retry with no reason leaves the card's text to the app's Retrying. */
    @Test
    fun retryCard_hasNoMessageWithoutAServerReason() {
        assertNull(SessionActivity.Retrying(nextAtMs = 1L, message = " ").retryCard()?.message)
        assertEquals("Slow down", SessionActivity.Retrying(nextAtMs = 1L, message = " Slow down ").retryCard()?.message)
    }
}
