package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.VcsFileStatus
import dev.opencode.mobile.ui.components.sessionBranchPillAccessibilityLabel
import dev.opencode.mobile.ui.components.sessionBranchPillAdditionLabel
import dev.opencode.mobile.ui.components.sessionBranchPillDeletionLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBranchPillVisibilityTest {
    @Test
    fun showsForKnownBranch() {
        val pill =
            SessionChatRepoStripRegionState(
                isAvailable = true,
                branch = "feature/pill",
            ).toSessionBranchPill()

        assertEquals(SessionBranchPillUiState("feature/pill", 0, 0), pill)
        assertTrue(SessionBranchPillVisibility.shouldShow(pill, hideFloatStack = false))
    }

    @Test
    fun hidesForEmptyOrUnavailableBranch() {
        assertNull(SessionChatRepoStripRegionState(isAvailable = false, branch = "main").toSessionBranchPill())
        assertNull(SessionChatRepoStripRegionState(isAvailable = true, branch = "").toSessionBranchPill())
        assertFalse(SessionBranchPillVisibility.shouldShow(null, hideFloatStack = false))
        assertFalse(
            SessionBranchPillVisibility.shouldShow(
                SessionBranchPillUiState("main", 1, 2),
                hideFloatStack = true,
            ),
        )
    }

    @Test
    fun countsOnlyWhenPositive() {
        val pill =
            SessionChatRepoStripRegionState(
                isAvailable = true,
                branch = "main",
                files =
                    listOf(
                        VcsFileStatus("a.kt", FileDiffStatus.Modified, 4, 1),
                        VcsFileStatus("b.kt", FileDiffStatus.Added, 2, 0),
                        VcsFileStatus("c.kt", FileDiffStatus.Deleted, 0, 3),
                    ),
            ).toSessionBranchPill()

        assertEquals(SessionBranchPillUiState("main", 6, 4), pill)
        assertEquals("+6", sessionBranchPillAdditionLabel(6))
        assertNull(sessionBranchPillAdditionLabel(0))
        assertEquals("−4", sessionBranchPillDeletionLabel(4))
        assertNull(sessionBranchPillDeletionLabel(0))
        assertEquals("Branch main", sessionBranchPillAccessibilityLabel("main", 0, 0))
        assertEquals("Branch main, 1 addition", sessionBranchPillAccessibilityLabel("main", 1, 0))
        assertEquals("Branch main, 2 deletions", sessionBranchPillAccessibilityLabel("main", 0, 2))
        assertEquals(
            "Branch feature/x, 4 additions, 1 deletion",
            sessionBranchPillAccessibilityLabel("feature/x", 4, 1),
        )
    }
}
