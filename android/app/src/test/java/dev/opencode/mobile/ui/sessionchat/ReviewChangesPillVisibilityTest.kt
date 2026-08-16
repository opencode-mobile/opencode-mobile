package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewChangesPillVisibilityTest {
    @Test
    fun showsWhenLatestTurnChangedFiles() {
        val summary = ReviewChangesSummary(fileCount = 2, additions = 4, deletions = 1)
        assertTrue(ReviewChangesPillVisibility.shouldShow(summary))
    }

    @Test
    fun hidesWhenSummaryMissingOrEmpty() {
        assertFalse(ReviewChangesPillVisibility.shouldShow(null))
        assertFalse(
            ReviewChangesPillVisibility.shouldShow(
                ReviewChangesSummary(fileCount = 0, additions = 0, deletions = 0),
            ),
        )
    }
}
