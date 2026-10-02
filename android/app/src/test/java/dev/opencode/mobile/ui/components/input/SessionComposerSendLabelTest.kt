package dev.opencode.mobile.ui.components

import dev.opencode.mobile.domain.message.FollowUpDelivery
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionComposerSendLabelTest {
    @Test
    fun idleSession_isPlainSend() {
        assertEquals("Send", sessionComposerSendLabel(FollowUpDelivery.Steer, hasActiveWork = false))
        assertEquals("Send", sessionComposerSendLabel(FollowUpDelivery.Queue, hasActiveWork = false))
        assertEquals("Send", sessionComposerSendLabel(null, hasActiveWork = false))
    }

    @Test
    fun activeWork_namesTheFollowUpDelivery() {
        assertEquals("Send as Steer", sessionComposerSendLabel(FollowUpDelivery.Steer, hasActiveWork = true))
        assertEquals("Send as Queue", sessionComposerSendLabel(FollowUpDelivery.Queue, hasActiveWork = true))
        assertEquals("Send", sessionComposerSendLabel(null, hasActiveWork = true))
    }
}
