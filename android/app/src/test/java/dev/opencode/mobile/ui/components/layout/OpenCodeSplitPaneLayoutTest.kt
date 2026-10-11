package dev.opencode.mobile.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeSplitPaneLayoutTest {
    @Test
    fun usesSplitLayout_startsAtTabletPortraitWidth() {
        assertFalse(OpenCodeSplitPaneLayout.usesSplitLayout(600.dp))
        assertFalse(OpenCodeSplitPaneLayout.usesSplitLayout(719.dp))
        assertTrue(OpenCodeSplitPaneLayout.usesSplitLayout(720.dp))
        assertTrue(OpenCodeSplitPaneLayout.usesSplitLayout(1280.dp))
    }

    @Test
    fun clamp_keepsWidthInsideBounds() {
        assertEquals(280.dp, OpenCodeSplitPaneLayout.clamp(100.dp))
        assertEquals(360.dp, OpenCodeSplitPaneLayout.clamp(360.dp))
        assertEquals(480.dp, OpenCodeSplitPaneLayout.clamp(900.dp))
    }

    @Test
    fun resolveDragEnd_openStartBelowCollapseThresholdSnapsClosedKeepingRememberedWidth() {
        val state =
            OpenCodeSplitPaneLayout.resolveDragEnd(
                startedCollapsed = false,
                rememberedWidth = 400.dp,
                proposedWidth = 219.dp,
            )
        assertEquals(OpenCodeSplitPaneState(leadingWidth = 400.dp, isCollapsed = true), state)
    }

    @Test
    fun resolveDragEnd_openStartBetweenThresholdAndMinimumClampsToMinimum() {
        val state =
            OpenCodeSplitPaneLayout.resolveDragEnd(
                startedCollapsed = false,
                rememberedWidth = 400.dp,
                proposedWidth = 240.dp,
            )
        assertEquals(OpenCodeSplitPaneState(leadingWidth = 280.dp, isCollapsed = false), state)
    }

    @Test
    fun resolveDragEnd_openStartAboveMaximumClampsToMaximum() {
        val state =
            OpenCodeSplitPaneLayout.resolveDragEnd(
                startedCollapsed = false,
                rememberedWidth = 360.dp,
                proposedWidth = 700.dp,
            )
        assertEquals(OpenCodeSplitPaneState(leadingWidth = 480.dp, isCollapsed = false), state)
    }

    @Test
    fun resolveDragEnd_collapsedStartPastReopenThresholdOpensAtRememberedWidth() {
        val state =
            OpenCodeSplitPaneLayout.resolveDragEnd(
                startedCollapsed = true,
                rememberedWidth = 420.dp,
                proposedWidth = 121.dp,
            )
        assertEquals(OpenCodeSplitPaneState(leadingWidth = 420.dp, isCollapsed = false), state)
    }

    @Test
    fun resolveDragEnd_collapsedStartBelowReopenThresholdStaysCollapsed() {
        val state =
            OpenCodeSplitPaneLayout.resolveDragEnd(
                startedCollapsed = true,
                rememberedWidth = 420.dp,
                proposedWidth = 120.dp,
            )
        assertEquals(OpenCodeSplitPaneState(leadingWidth = 420.dp, isCollapsed = true), state)
    }

    @Test
    fun toggled_flipsCollapsedAndKeepsWidth() {
        val open = OpenCodeSplitPaneState(leadingWidth = 300.dp, isCollapsed = false)
        val collapsed = OpenCodeSplitPaneLayout.toggled(open)
        assertEquals(OpenCodeSplitPaneState(leadingWidth = 300.dp, isCollapsed = true), collapsed)
        assertEquals(open, OpenCodeSplitPaneLayout.toggled(collapsed))
    }

    @Test
    fun defaultState_isOpenAtDefaultWidth() {
        assertEquals(
            OpenCodeSplitPaneState(leadingWidth = 360.dp, isCollapsed = false),
            OpenCodeSplitPaneState(),
        )
    }

    @Test
    fun usesContentSplit_requiresTwoColumnsOf500() {
        assertFalse(OpenCodeSplitPaneLayout.usesContentSplit(834.dp))
        assertFalse(OpenCodeSplitPaneLayout.usesContentSplit(1000.dp))
        assertTrue(OpenCodeSplitPaneLayout.usesContentSplit(1001.dp))
        assertTrue(OpenCodeSplitPaneLayout.usesContentSplit(1194.dp))
    }

    @Test
    fun contentBounds_keepBothColumnsAtLeast500() {
        val bounds = OpenCodeSplitPaneLayout.contentBounds(1194.dp)
        assertEquals(500.dp, bounds.minLeading)
        assertEquals(693.dp, bounds.maxLeading)
        val narrow =
            OpenCodeSplitPaneLayout.resolveDragEnd(
                startedCollapsed = false,
                rememberedWidth = 560.dp,
                proposedWidth = 300.dp,
                bounds = bounds,
            )
        assertEquals(OpenCodeSplitPaneState(leadingWidth = 500.dp, isCollapsed = false), narrow)
        val wide =
            OpenCodeSplitPaneLayout.resolveDragEnd(
                startedCollapsed = false,
                rememberedWidth = 560.dp,
                proposedWidth = 900.dp,
                bounds = bounds,
            )
        assertEquals(693.dp, wide.leadingWidth)
        assertTrue(1194.dp - wide.leadingWidth - OpenCodeSplitPaneLayout.DividerWidth >= 500.dp)
    }

    @Test
    fun contentDrag_belowCollapseThresholdSnapsClosed() {
        val bounds = OpenCodeSplitPaneLayout.contentBounds(1180.dp)
        val state =
            OpenCodeSplitPaneLayout.resolveDragEnd(
                startedCollapsed = false,
                rememberedWidth = 560.dp,
                proposedWidth = 200.dp,
                bounds = bounds,
            )
        assertEquals(OpenCodeSplitPaneState(leadingWidth = 560.dp, isCollapsed = true), state)
    }

    @Test
    fun displayedCollapsed_overlaysNestedSplitWithoutReplacingPersistedFlag() {
        assertTrue(OpenCodeSplitPaneLayout.displayedCollapsed(persisted = false, nestedContentSplitVisible = true))
        assertTrue(OpenCodeSplitPaneLayout.displayedCollapsed(persisted = true, nestedContentSplitVisible = false))
        assertFalse(OpenCodeSplitPaneLayout.displayedCollapsed(persisted = false, nestedContentSplitVisible = false))
    }
}
