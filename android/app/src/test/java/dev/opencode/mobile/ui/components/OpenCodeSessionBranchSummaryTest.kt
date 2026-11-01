package dev.opencode.mobile.ui.components

import dev.opencode.mobile.ui.icons.OpenCodeIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeSessionBranchSummaryTest {
    private val files =
        listOf(
            OpenCodeSessionBranchChangedFile("b.kt", 100, 2),
            OpenCodeSessionBranchChangedFile("a.kt", 20, 10),
            OpenCodeSessionBranchChangedFile("c.md", 8, 2),
        )

    @Test
    fun changes_showIconAndCountsWithoutBranchName() {
        val summary = OpenCodeSessionBranchSummary("feature-x", baseBranch = "main", files = files)

        assertTrue(summary.hasChanges)
        assertEquals("+128 −14", summary.changeLabel)
        assertFalse(summary.changeLabel!!.contains("feature-x"))
        assertEquals(OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.GitBranch, "+128 −14"), summary.pillLayout)
        assertEquals("Branch feature-x, 128 added, 14 removed", summary.accessibilityLabel)
    }

    @Test
    fun noChanges_showsIconOnly() {
        val summary = OpenCodeSessionBranchSummary("feature-x", baseBranch = "main")

        assertFalse(summary.hasChanges)
        assertNull(summary.changeLabel)
        assertEquals("Branch feature-x, no changes", summary.accessibilityLabel)
        assertEquals(
            OpenCodeFloatingPillLayout.IconOnly(OpenCodeIcons.GitBranch, "Branch feature-x, no changes"),
            summary.pillLayout,
        )
        assertNull(summary.fileCountLabel)
        assertTrue(summary.topFiles.isEmpty())
    }

    @Test
    fun worktree_usesWorktreeIcon() {
        val summary = OpenCodeSessionBranchSummary("feature-x", isWorktree = true, files = files)

        assertEquals(OpenCodeIcons.CallSplit, summary.icon)
        assertEquals(OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.CallSplit, "+128 −14"), summary.pillLayout)
        assertEquals("Worktree branch feature-x, 128 added, 14 removed", summary.accessibilityLabel)
        assertEquals(
            OpenCodeFloatingPillLayout.IconOnly(OpenCodeIcons.CallSplit, "Worktree branch feature-x, no changes"),
            OpenCodeSessionBranchSummary("feature-x", isWorktree = true).pillLayout,
        )
    }

    @Test
    fun zeroSidesAreLeftOut() {
        val added = OpenCodeSessionBranchSummary("x", files = listOf(OpenCodeSessionBranchChangedFile("a", 5, 0)))
        val removed = OpenCodeSessionBranchSummary("x", files = listOf(OpenCodeSessionBranchChangedFile("a", 0, 3)))
        val binary =
            OpenCodeSessionBranchSummary(
                "x",
                files = listOf(OpenCodeSessionBranchChangedFile("a.png", 0, 0), OpenCodeSessionBranchChangedFile("b.png", 0, 0)),
            )

        assertEquals("+5", added.changeLabel)
        assertEquals("Branch x, 5 added", added.accessibilityLabel)
        assertEquals("−3", removed.changeLabel)
        assertEquals('−', removed.changeLabel!!.first())
        assertEquals("2 files", binary.changeLabel)
        assertEquals("Branch x, 2 uncommitted files", binary.accessibilityLabel)
    }

    @Test
    fun topFiles_areTheThreeMostChangedByLines() {
        val summary =
            OpenCodeSessionBranchSummary(
                "x",
                files =
                    files +
                        listOf(
                            OpenCodeSessionBranchChangedFile("small.txt", 1, 0),
                            OpenCodeSessionBranchChangedFile("big.kt", 0, 50),
                        ),
            )

        assertEquals(listOf("b.kt", "big.kt", "a.kt"), summary.topFiles.map { it.path })
        assertEquals("5 uncommitted files", summary.fileCountLabel)
    }

    @Test
    fun topFiles_tiesSortByPath() {
        val summary =
            OpenCodeSessionBranchSummary(
                "x",
                files = listOf(OpenCodeSessionBranchChangedFile("z", 2, 0), OpenCodeSessionBranchChangedFile("y", 1, 1)),
            )

        assertEquals(listOf("y", "z"), summary.topFiles.map { it.path })
        assertEquals("2 uncommitted files", summary.fileCountLabel)
        assertEquals(
            "1 uncommitted file",
            OpenCodeSessionBranchSummary("x", files = listOf(OpenCodeSessionBranchChangedFile("a", 1, 0))).fileCountLabel,
        )
    }

    @Test
    fun baseBranch_showsOnlyWhenKnownAndDifferent() {
        assertEquals("main", OpenCodeSessionBranchSummary("feature-x", baseBranch = "main").visibleBaseBranch)
        assertNull(OpenCodeSessionBranchSummary("main", baseBranch = "main").visibleBaseBranch)
        assertNull(OpenCodeSessionBranchSummary("feature-x", baseBranch = null).visibleBaseBranch)
        assertNull(OpenCodeSessionBranchSummary("feature-x", baseBranch = "").visibleBaseBranch)
    }

    @Test
    fun popover_hidesZeroSidesAndNeverSpeaksZero() {
        val deletionOnly = OpenCodeSessionBranchChangedFile("src/old.ts", 0, 42)
        val binary = OpenCodeSessionBranchChangedFile("assets/logo.png", 0, 0)
        val summary = OpenCodeSessionBranchSummary("x", files = listOf(deletionOnly, binary))

        assertNull(deletionOnly.visibleAdditions)
        assertEquals(42, deletionOnly.visibleDeletions)
        assertEquals("src/old.ts, 42 removed", deletionOnly.accessibilityLabel)
        assertNull(binary.visibleAdditions)
        assertNull(binary.visibleDeletions)
        assertEquals("assets/logo.png", binary.accessibilityLabel)
        assertNull(summary.visibleAdditions)
        assertEquals(42, summary.visibleDeletions)
        assertEquals("2 uncommitted files, 42 removed", summary.fileCountAccessibilityLabel)
        assertEquals(listOf("src/old.ts", "assets/logo.png"), summary.topFiles.map { it.path })
        listOf(deletionOnly.accessibilityLabel, binary.accessibilityLabel, summary.fileCountAccessibilityLabel!!, summary.accessibilityLabel)
            .forEach {
                assertFalse(it.contains("0 added"))
                assertFalse(it.contains("0 removed"))
            }
    }

    @Test
    fun popover_totalsSpeakBothSidesWhenPresent() {
        val summary = OpenCodeSessionBranchSummary("x", files = files)

        assertEquals(128, summary.visibleAdditions)
        assertEquals(14, summary.visibleDeletions)
        assertEquals("3 uncommitted files, 128 added, 14 removed", summary.fileCountAccessibilityLabel)
        assertEquals("b.kt, 100 added, 2 removed", files[0].accessibilityLabel)
    }
}
