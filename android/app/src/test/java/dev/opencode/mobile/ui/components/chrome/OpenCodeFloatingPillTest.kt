package dev.opencode.mobile.ui.components

import dev.opencode.mobile.ui.icons.OpenCodeIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
            floatingPillLeading(
                OpenCodeFloatingPillLayout.IconOnly(OpenCodeIcons.ArrowDownward, "Scroll"),
                OpenCodeFloatingPillState.Error,
            ),
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

    @Test
    fun accessibilityOverride_replacesLabelAndState() {
        assertEquals(
            "Branch changes, 128 added, 14 removed",
            floatingPillDescription(
                OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.CallSplit, "+128 −14"),
                OpenCodeFloatingPillState.Ready,
                override = "Branch changes, 128 added, 14 removed",
            ),
        )
        assertEquals(
            "Dismiss Side question failed",
            floatingPillCloseDescription(OpenCodeFloatingPillLayout.Closable("/btw failed"), override = "Side question failed"),
        )
    }

    @Test
    fun compact_keepsTheRegularMark() {
        assertEquals(
            OpenCodeFloatingPillLeading.Spinner,
            floatingPillCompactLeading(OpenCodeFloatingPillLayout.Counter("3 subagents"), OpenCodeFloatingPillState.Live),
        )
        assertEquals(
            OpenCodeFloatingPillLeading.PulsingDot,
            floatingPillCompactLeading(
                OpenCodeFloatingPillLayout.Counter("general", waiting = true),
                OpenCodeFloatingPillState.NeedsAttention,
            ),
        )
        assertEquals(
            OpenCodeFloatingPillLeading.Icon(OpenCodeIcons.List),
            floatingPillCompactLeading(OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.List, "2/5"), OpenCodeFloatingPillState.Ready),
        )
        assertEquals(
            OpenCodeFloatingPillLeading.DoneCheck,
            floatingPillCompactLeading(OpenCodeFloatingPillLayout.Closable("Request failed"), OpenCodeFloatingPillState.Done),
        )
    }

    @Test
    fun compact_hasAMarkInEveryLayoutAndState() {
        allLayouts.forEach { layout ->
            OpenCodeFloatingPillState.entries.forEach { state ->
                assertNotEquals(OpenCodeFloatingPillLeading.None, floatingPillCompactLeading(layout, state))
            }
        }
        assertEquals(
            OpenCodeFloatingPillLeading.StateDot,
            floatingPillCompactLeading(OpenCodeFloatingPillLayout.Closable("Request failed"), OpenCodeFloatingPillState.Error),
        )
        assertEquals(
            OpenCodeFloatingPillLeading.StateDot,
            floatingPillCompactLeading(OpenCodeFloatingPillLayout.Label("+3"), OpenCodeFloatingPillState.Ready),
        )
    }

    @Test
    fun compact_hidesClose() {
        val closable = OpenCodeFloatingPillLayout.Closable("Request failed")
        assertTrue(floatingPillShowsClose(closable, compact = false, hasCloseAction = true))
        assertFalse(floatingPillShowsClose(closable, compact = true, hasCloseAction = true))
        assertFalse(floatingPillShowsClose(closable, compact = false, hasCloseAction = false))
        assertFalse(floatingPillShowsClose(OpenCodeFloatingPillLayout.Label("+3"), compact = false, hasCloseAction = true))
    }

    @Test
    fun labelLayout_isPlainText() {
        OpenCodeFloatingPillState.entries.filter { it != OpenCodeFloatingPillState.Done }.forEach { state ->
            assertEquals(OpenCodeFloatingPillLeading.None, floatingPillLeading(OpenCodeFloatingPillLayout.Label("+3"), state))
        }
        assertEquals("+3, Ready", floatingPillDescription(OpenCodeFloatingPillLayout.Label("+3"), OpenCodeFloatingPillState.Ready))
    }

    @Test
    fun onlyLiveOrWaitingCountersAnnounceUpdates() {
        allLayouts.forEach { layout ->
            OpenCodeFloatingPillState.entries.forEach { state ->
                val expected =
                    layout is OpenCodeFloatingPillLayout.Counter &&
                        (state == OpenCodeFloatingPillState.Live || state == OpenCodeFloatingPillState.NeedsAttention)
                assertEquals(expected, floatingPillAnnouncesUpdates(layout, state))
            }
        }
    }

    private val allLayouts =
        listOf(
            OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.CallSplit, "+128 −14"),
            OpenCodeFloatingPillLayout.IconOnly(OpenCodeIcons.ArrowDownward, "Scroll to end"),
            OpenCodeFloatingPillLayout.Counter("3 subagents"),
            OpenCodeFloatingPillLayout.Counter("general needs input", waiting = true),
            OpenCodeFloatingPillLayout.Closable("Request failed"),
            OpenCodeFloatingPillLayout.Label("+3"),
        )
}
