package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSendProgressTest {
    @Test
    fun percent_capsBelowOneHundredUntilComplete() {
        assertEquals(25, PromptSendProgress.percent(1, 4))
        assertEquals(99, PromptSendProgress.percent(4, 4))
        assertEquals(0, PromptSendProgress.percent(0, 0))
    }

    @Test
    fun shouldShow_attachmentsOrLargeBody() {
        assertTrue(PromptSendProgress.shouldShow(10, hasAttachments = true))
        assertTrue(PromptSendProgress.shouldShow(PromptSendProgress.VISIBLE_BODY_THRESHOLD, hasAttachments = false))
        assertFalse(PromptSendProgress.shouldShow(10, hasAttachments = false))
    }

    @Test
    fun label_ellipsisThenPercent() {
        assertEquals("Sending…", PromptSendProgress.label(0))
        assertEquals("Sending 25%", PromptSendProgress.label(25))
    }
}
