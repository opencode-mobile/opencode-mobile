package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.VcsFileStatus
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchChangedFile
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBranchPillVisibilityTest {
    @Test
    fun showsIconOnlyPillForCleanKnownBranch() {
        val pill =
            SessionChatRepoStripRegionState(
                isAvailable = true,
                branch = "feature/pill",
            ).toSessionBranchPill()

        assertEquals("feature/pill", pill?.branch)
        assertEquals(0, pill?.additions)
        assertNull(pill?.summary?.changeLabel)
        assertTrue(SessionBranchPillVisibility.shouldShow(pill, hideFloatStack = false))
    }

    @Test
    fun hidesForEmptyOrUnavailableBranch() {
        assertNull(SessionChatRepoStripRegionState(isAvailable = false, branch = "main").toSessionBranchPill())
        assertNull(SessionChatRepoStripRegionState(isAvailable = true, branch = "").toSessionBranchPill())
        assertFalse(SessionBranchPillVisibility.shouldShow(null, hideFloatStack = false))
        assertFalse(
            SessionBranchPillVisibility.shouldShow(
                SessionChatRepoStripRegionState(isAvailable = true, branch = "main").toSessionBranchPill(),
                hideFloatStack = true,
            ),
        )
    }

    @Test
    fun carriesFilesBaseAndSums() {
        val pill =
            SessionChatRepoStripRegionState(
                isAvailable = true,
                branch = "fix/probe",
                defaultBranch = "main",
                files =
                    listOf(
                        VcsFileStatus("a.kt", FileDiffStatus.Modified, 4, 1),
                        VcsFileStatus("b.kt", FileDiffStatus.Added, 2, 0),
                        VcsFileStatus("c.kt", FileDiffStatus.Deleted, 0, 3),
                    ),
            ).toSessionBranchPill()

        assertEquals(6, pill?.additions)
        assertEquals(4, pill?.deletions)
        assertEquals("main", pill?.summary?.visibleBaseBranch)
        assertEquals(
            listOf(
                OpenCodeSessionBranchChangedFile("a.kt", 4, 1),
                OpenCodeSessionBranchChangedFile("b.kt", 2, 0),
                OpenCodeSessionBranchChangedFile("c.kt", 0, 3),
            ),
            pill?.summary?.files,
        )
        assertFalse(pill?.summary?.isWorktree ?: true)
    }

    @Test
    fun worktreeCheckoutMarksTheSummary() {
        val pill =
            SessionChatRepoStripRegionState(isAvailable = true, branch = "fix/probe")
                .toSessionBranchPill(isWorktree = true)

        assertTrue(pill?.summary?.isWorktree == true)
        assertEquals(OpenCodeIcons.CallSplit, pill?.summary?.icon)
    }
}
