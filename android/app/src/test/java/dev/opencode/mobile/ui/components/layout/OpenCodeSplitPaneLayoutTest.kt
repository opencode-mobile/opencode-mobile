package dev.opencode.mobile.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeSplitPaneLayoutTest {
    @Test
    fun usesSplitLayout_startsAtTabletPortraitWidth() {
        val tablet = 800.dp
        assertFalse(OpenCodeSplitPaneLayout.usesSplitLayout(600.dp, smallestScreenWidth = tablet))
        assertFalse(OpenCodeSplitPaneLayout.usesSplitLayout(719.dp, smallestScreenWidth = tablet))
        assertTrue(OpenCodeSplitPaneLayout.usesSplitLayout(720.dp, smallestScreenWidth = tablet))
        assertTrue(OpenCodeSplitPaneLayout.usesSplitLayout(1280.dp, smallestScreenWidth = tablet))
    }

    @Test
    fun usesSplitLayout_phoneStaysStackedInWideLandscape() {
        assertFalse(OpenCodeSplitPaneLayout.usesSplitLayout(780.dp, smallestScreenWidth = 360.dp))
        assertFalse(OpenCodeSplitPaneLayout.usesSplitLayout(932.dp, smallestScreenWidth = 430.dp))
        assertFalse(OpenCodeSplitPaneLayout.usesSplitLayout(1280.dp, smallestScreenWidth = 599.dp))
    }

    @Test
    fun usesSplitLayout_tabletClassStartsAtSmallestWidth600() {
        assertTrue(OpenCodeSplitPaneLayout.usesSplitLayout(720.dp, smallestScreenWidth = 600.dp))
        // Unfolded foldable inner display.
        assertTrue(OpenCodeSplitPaneLayout.usesSplitLayout(841.dp, smallestScreenWidth = 673.dp))
        // Tablet window narrowed by multi-window stays stacked.
        assertFalse(OpenCodeSplitPaneLayout.usesSplitLayout(600.dp, smallestScreenWidth = 600.dp))
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
    fun everyScreen_usesTheHomeSidebarRange() {
        val bounds = OpenCodeSplitPaneLayout.sidebarBounds
        assertEquals(280.dp, bounds.minLeading)
        assertEquals(480.dp, bounds.maxLeading)
        assertEquals(220.dp, bounds.collapseThreshold)
        assertFalse(OpenCodeSplitPaneScreen.Home.collapsesAncestorSidebar)
        assertFalse(OpenCodeSplitPaneScreen.Settings.collapsesAncestorSidebar)
        assertTrue(OpenCodeSplitPaneScreen.Changes.collapsesAncestorSidebar)
        assertTrue(OpenCodeSplitPaneScreen.Terminals.collapsesAncestorSidebar)
        assertTrue(OpenCodeSplitPaneScreen.Files.collapsesAncestorSidebar)
        val resized =
            OpenCodeSplitPaneLayout.resolveDragEnd(
                startedCollapsed = false,
                rememberedWidth = 360.dp,
                proposedWidth = 300.dp,
                bounds = bounds,
            )
        assertEquals(OpenCodeSplitPaneState(leadingWidth = 300.dp, isCollapsed = false), resized)
    }

    @Test
    fun displayedCollapsed_overlaysNestedSplitWithoutReplacingPersistedFlag() {
        assertTrue(OpenCodeSplitPaneLayout.displayedCollapsed(persisted = false, nestedContentSplitVisible = true))
        assertTrue(OpenCodeSplitPaneLayout.displayedCollapsed(persisted = true, nestedContentSplitVisible = false))
        assertFalse(OpenCodeSplitPaneLayout.displayedCollapsed(persisted = false, nestedContentSplitVisible = false))
    }
}
