package dev.opencode.mobile.ui.scroll

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListInitialEndSnapTest {
    @Test
    fun firstNonEmptyTimeline_snaps() {
        assertTrue(MessageListInitialEndSnapState().shouldSnap(messageCount = 3))
    }

    @Test
    fun emptyTimeline_doesNotSnap() {
        assertFalse(MessageListInitialEndSnapState().shouldSnap(messageCount = 0))
    }

    @Test
    fun afterInitialSnap_newLastMessageDoesNotSnapAgain() {
        val afterFirstLayout = MessageListInitialEndSnapState().markSnapped()

        assertFalse(afterFirstLayout.shouldSnap(messageCount = 4))
    }
}
