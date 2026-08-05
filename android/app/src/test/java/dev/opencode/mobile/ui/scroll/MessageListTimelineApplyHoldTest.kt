package dev.opencode.mobile.ui.scroll

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListTimelineApplyHoldTest {
    @Test
    fun shouldHold_userScroll_nonRequired_holds() {
        assertTrue(
            MessageListTimelineApplyHold.shouldHold(
                isUserScrollInProgress = true,
                isRequired = false,
            ),
        )
    }

    @Test
    fun shouldHold_userScroll_required_flushes() {
        assertFalse(
            MessageListTimelineApplyHold.shouldHold(
                isUserScrollInProgress = true,
                isRequired = true,
            ),
        )
    }

    @Test
    fun shouldHold_idleOrProgrammatic_commits() {
        // Idle and programmatic auto-follow both report false here so streaming
        // frames keep applying (unlike listState.isScrollInProgress).
        assertFalse(
            MessageListTimelineApplyHold.shouldHold(
                isUserScrollInProgress = false,
                isRequired = false,
            ),
        )
        assertFalse(
            MessageListTimelineApplyHold.shouldHold(
                isUserScrollInProgress = false,
                isRequired = true,
            ),
        )
    }
}
