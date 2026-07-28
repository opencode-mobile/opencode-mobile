package dev.opencode.mobile.ui.scroll

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListTimelineApplyHoldTest {
    @Test
    fun shouldHold_scrollInProgress_nonRequired_holds() {
        assertTrue(
            MessageListTimelineApplyHold.shouldHold(
                isScrollInProgress = true,
                isRequired = false,
            ),
        )
    }

    @Test
    fun shouldHold_scrollInProgress_required_flushes() {
        assertFalse(
            MessageListTimelineApplyHold.shouldHold(
                isScrollInProgress = true,
                isRequired = true,
            ),
        )
    }

    @Test
    fun shouldHold_idle_commits() {
        assertFalse(
            MessageListTimelineApplyHold.shouldHold(
                isScrollInProgress = false,
                isRequired = false,
            ),
        )
        assertFalse(
            MessageListTimelineApplyHold.shouldHold(
                isScrollInProgress = false,
                isRequired = true,
            ),
        )
    }
}
