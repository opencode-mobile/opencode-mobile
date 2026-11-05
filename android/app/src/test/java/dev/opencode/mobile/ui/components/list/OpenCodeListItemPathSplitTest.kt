package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeListItemPathSplitTest {
    @Test
    fun bothFit_keepFullWidths() {
        assertEquals(OpenCodeListItemPathSplit.Widths(60f, 30f), OpenCodeListItemPathSplit.widths(60f, 30f, available = 100f))
        assertEquals(OpenCodeListItemPathSplit.Widths(70f, 30f), OpenCodeListItemPathSplit.widths(70f, 30f, available = 100f))
    }

    @Test
    fun shortProject_keepsItsWidth() {
        // 40 fits in 40% of 100; the worktree takes the rest.
        assertEquals(OpenCodeListItemPathSplit.Widths(40f, 60f), OpenCodeListItemPathSplit.widths(40f, 200f, available = 100f))
        assertEquals(OpenCodeListItemPathSplit.Widths(20f, 80f), OpenCodeListItemPathSplit.widths(20f, 200f, available = 100f))
    }

    @Test
    fun longProjectShortWorktree_worktreeKeepsItsWidth() {
        assertEquals(OpenCodeListItemPathSplit.Widths(45f, 55f), OpenCodeListItemPathSplit.widths(200f, 55f, available = 100f))
        assertEquals(OpenCodeListItemPathSplit.Widths(40f, 60f), OpenCodeListItemPathSplit.widths(200f, 60f, available = 100f))
    }

    @Test
    fun bothLong_split40By60() {
        assertEquals(OpenCodeListItemPathSplit.Widths(40f, 60f), OpenCodeListItemPathSplit.widths(41f, 61f, available = 100f))
        assertEquals(OpenCodeListItemPathSplit.Widths(80f, 120f), OpenCodeListItemPathSplit.widths(500f, 500f, available = 200f))
    }

    @Test
    fun middleCut_keepsTicketNumberAndEnd() {
        assertEquals(
            "ope-1042-sh…n-home-rows",
            OpenCodeListItemPathSplit.middleCut("ope-1042-show-worktree-and-project-on-home-rows", kept = 22),
        )
        assertEquals("abc", OpenCodeListItemPathSplit.middleCut("abc", kept = 5))
    }

    @Test
    fun middleCutToFit_returnsLongestCutThatFits() {
        val name = "806-clear-the-worktree-filter-when-it-is-empty"
        val cut = OpenCodeListItemPathSplit.middleCutToFit(name) { it.length <= 20 }

        assertEquals("806-clear-…-is-empty", cut)
        assertEquals(20, cut.length)
    }

    @Test
    fun endCutToFit_keepsTheStart() {
        assertEquals("artifact-pub…", OpenCodeListItemPathSplit.endCutToFit("artifact-publisher-dashboard-service") { it.length <= 13 })
        assertEquals("dotfiles", OpenCodeListItemPathSplit.endCutToFit("dotfiles") { it.length <= 13 })
        assertEquals("a…", OpenCodeListItemPathSplit.endCutToFit("artifact") { false })
    }

    @Test
    fun middleCutToFit_leavesFittingNameWhole() {
        assertEquals("live-reload", OpenCodeListItemPathSplit.middleCutToFit("live-reload") { true })
    }

    @Test
    fun middleCutToFit_fallsBackToShortestCut() {
        assertEquals("abc…de", OpenCodeListItemPathSplit.middleCutToFit("abcdefghijkde") { false })
    }
}
