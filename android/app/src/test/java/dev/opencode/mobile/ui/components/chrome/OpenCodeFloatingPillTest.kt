package dev.opencode.mobile.ui.components

import dev.opencode.mobile.ui.icons.OpenCodeIcons
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeFloatingPillTest {
    @Test
    fun counter_spinsWhileLive() {
        assertEquals(
            OpenCodeFloatingPillLeading.Spinner,
            floatingPillLeading(OpenCodeFloatingPillLayout.Counter("3 subagents"), OpenCodeFloatingPillState.Live),
        )
    }

    @Test
    fun counter_waitingItemSwapsSpinnerForPulsingDot() {
        assertEquals(
            OpenCodeFloatingPillLeading.PulsingDot,
            floatingPillLeading(
                OpenCodeFloatingPillLayout.Counter("general needs input", waiting = true),
                OpenCodeFloatingPillState.NeedsAttention,
            ),
        )
        assertEquals(
            OpenCodeFloatingPillLeading.Spinner,
            floatingPillLeading(OpenCodeFloatingPillLayout.Counter("2 running"), OpenCodeFloatingPillState.NeedsAttention),
        )
    }

    @Test
    fun done_showsCheckInEveryLayout() {
        listOf(
            OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.CallSplit, "main"),
            OpenCodeFloatingPillLayout.IconOnly(OpenCodeIcons.ArrowDownward, "Scroll to end"),
            OpenCodeFloatingPillLayout.Counter("3 subagents"),
            OpenCodeFloatingPillLayout.Closable("Request failed"),
        ).forEach { layout ->
            assertEquals(OpenCodeFloatingPillLeading.DoneCheck, floatingPillLeading(layout, OpenCodeFloatingPillState.Done))
        }
    }

    @Test
    fun iconLayouts_keepTheirIconOutsideDone() {
        assertEquals(
            OpenCodeFloatingPillLeading.Icon(OpenCodeIcons.List),
            floatingPillLeading(OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.List, "2/5"), OpenCodeFloatingPillState.Ready),
        )
        assertEquals(
            OpenCodeFloatingPillLeading.Icon(OpenCodeIcons.ArrowDownward),
            floatingPillLeading(OpenCodeFloatingPillLayout.IconOnly(OpenCodeIcons.ArrowDownward, "Scroll"), OpenCodeFloatingPillState.Error),
        )
        assertEquals(
            OpenCodeFloatingPillLeading.None,
            floatingPillLeading(OpenCodeFloatingPillLayout.Closable("Request failed"), OpenCodeFloatingPillState.Error),
        )
    }

    @Test
    fun tone_andRingFollowState() {
        assertEquals(OpenCodeFloatingPillTone.Base, floatingPillTone(OpenCodeFloatingPillState.Live))
        assertEquals(OpenCodeFloatingPillTone.Strong, floatingPillTone(OpenCodeFloatingPillState.Ready))
        assertEquals(OpenCodeFloatingPillTone.Warning, floatingPillTone(OpenCodeFloatingPillState.NeedsAttention))
        assertEquals(OpenCodeFloatingPillTone.Error, floatingPillTone(OpenCodeFloatingPillState.Error))
        assertEquals(OpenCodeFloatingPillTone.Strong, floatingPillTone(OpenCodeFloatingPillState.Done))
        assertEquals(
            listOf(OpenCodeFloatingPillState.NeedsAttention),
            OpenCodeFloatingPillState.entries.filter(::floatingPillHasRing),
        )
    }

    @Test
    fun accessibility_speaksLabelAndState() {
        assertEquals(
            "3 subagents, In progress",
            floatingPillDescription(OpenCodeFloatingPillLayout.Counter("3 subagents"), OpenCodeFloatingPillState.Live),
        )
        assertEquals(
            "Scroll to end, Ready",
            floatingPillDescription(
                OpenCodeFloatingPillLayout.IconOnly(OpenCodeIcons.ArrowDownward, "Scroll to end"),
                OpenCodeFloatingPillState.Ready,
            ),
        )
        assertEquals("Dismiss Request failed", floatingPillCloseDescription(OpenCodeFloatingPillLayout.Closable("Request failed")))
    }
}
