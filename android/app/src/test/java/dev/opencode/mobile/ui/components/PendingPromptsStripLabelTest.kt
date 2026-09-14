package dev.opencode.mobile.ui.components

import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.ui.sessionchat.PendingPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPromptsStripLabelTest {
    @Test
    fun format_primaryIsCollapsedFirstText() {
        val label =
            formatPendingPromptsStripLabel(
                listOf(
                    PendingPrompt("1", "first line\nsecond line", FollowUpDelivery.Steer),
                    PendingPrompt("2", "other", FollowUpDelivery.Queue),
                    PendingPrompt("3", "third", FollowUpDelivery.Queue),
                ),
            )
        assertEquals("first line second line", label.primary)
        assertEquals("+2 queued", label.secondary)
    }

    @Test
    fun format_singleItemHasNoSecondary() {
        val label =
            formatPendingPromptsStripLabel(
                listOf(
                    PendingPrompt("1", "also check the compact path", FollowUpDelivery.Queue),
                ),
            )
        assertEquals("also check the compact path", label.primary)
        assertNull(label.secondary)
    }

    @Test
    fun contentDescription_singularAndPlural() {
        assertEquals(
            "1 pending prompt, next: also check the compact path",
            formatPendingPromptsStripContentDescription(
                listOf(
                    PendingPrompt("1", "also check the compact path", FollowUpDelivery.Steer),
                ),
            ),
        )
        assertEquals(
            "3 pending prompts, next: first line",
            formatPendingPromptsStripContentDescription(
                listOf(
                    PendingPrompt("1", "first line", FollowUpDelivery.Steer),
                    PendingPrompt("2", "second", FollowUpDelivery.Queue),
                    PendingPrompt("3", "third", FollowUpDelivery.Queue),
                ),
            ),
        )
    }
}
