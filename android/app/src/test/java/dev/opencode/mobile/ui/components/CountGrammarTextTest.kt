package dev.opencode.mobile.ui.components

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.text.EnglishStrings
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Test

/** Counts read in the singular for 1 and keep their plural English otherwise (#886). */
class CountGrammarTextTest {
    @Test
    fun activityCardAgentCount() {
        assertEquals("1 agent", UiText.plural(R.plurals.component_activity_card_agent_count, 1).english())
        assertEquals("3 agents", UiText.plural(R.plurals.component_activity_card_agent_count, 3).english())
    }

    @Test
    fun namedToolCountKeepsTheServerName() {
        assertEquals("3 mcp_lookup", UiText.plural(R.plurals.tool_count_named, 3, 3, "mcp_lookup").english())
    }

    @Test
    fun mcpHeadlineFollowsTheConfiguredCount() {
        assertEquals("1 of 1 connected", UiText.plural(R.plurals.session_chat_sheet_mcp_headline, 1, 1, 1).english())
        assertEquals("2 of 3 connected", UiText.plural(R.plurals.session_chat_sheet_mcp_headline, 3, 2, 3).english())
    }

    @Test
    fun unifiedDiffSummaryCountsAdditionsAndDeletions() {
        fun summary(
            additions: Int,
            deletions: Int,
        ) = UiText
            .resource(
                R.string.component_unified_diff_summary_modified,
                "src/a.kt",
                UiText.plural(R.plurals.changes_count_additions, additions),
                UiText.plural(R.plurals.changes_count_deletions, deletions),
            ).english()
        assertEquals("Modified file src/a.kt, 1 addition, 1 deletion", summary(1, 1))
        assertEquals("Modified file src/a.kt, 3 additions, 0 deletions", summary(3, 0))
    }

    @Test
    fun reviewChangesPillSingularAndPlural() {
        assertEquals(
            "Review changes, 1 file, 1 addition, 1 deletion",
            reviewChangesPillAccessibilityLabel(1, 1, 1, EnglishStrings),
        )
        assertEquals(
            "Review changes, 5 files, 85 additions, 34 deletions",
            reviewChangesPillAccessibilityLabel(5, 85, 34, EnglishStrings),
        )
        assertEquals("Review changes, 4 files", reviewChangesPillAccessibilityLabel(4, 0, 0, EnglishStrings))
        assertEquals("1 file  +1  −1", reviewChangesPillSummary(1, 1, 1, EnglishStrings))
        assertEquals("4 files  +12", reviewChangesPillSummary(4, 12, 0, EnglishStrings))
    }
}
