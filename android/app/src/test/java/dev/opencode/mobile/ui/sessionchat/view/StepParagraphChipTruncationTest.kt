package dev.opencode.mobile.ui.sessionchat.view

import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.ui.sessionchat.PresentationBlockId
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.ToolChipFamily
import dev.opencode.mobile.ui.sessionchat.ToolChipKind
import org.junit.Assert.assertEquals
import org.junit.Test

class StepParagraphChipTruncationTest {
    @Test
    fun exploreShowsTwo_changeShowsThree_othersNeverTruncate() {
        val chips =
            listOf(
                chip("skill", ToolChipFamily.Skill),
                chip("e1", ToolChipFamily.Explore),
                chip("e2", ToolChipFamily.Explore),
                chip("e3", ToolChipFamily.Explore),
                chip("a1", ToolChipFamily.Agent),
                chip("a2", ToolChipFamily.Agent),
                chip("c1", ToolChipFamily.Change),
                chip("c2", ToolChipFamily.Change),
                chip("c3", ToolChipFamily.Change),
                chip("c4", ToolChipFamily.Change),
                chip("x1", ToolChipFamily.Command),
                chip("x2", ToolChipFamily.Command),
                chip("x3", ToolChipFamily.Command),
                chip("x4", ToolChipFamily.Command),
            )

        val visible = visibleStepParagraphChips(chips, showAll = false)

        assertEquals(
            listOf("skill", "e1", "e2", "a1", "a2", "c1", "c2", "c3", "x1", "x2", "x3", "x4"),
            visible.map { it.label },
        )
        assertEquals(2, chips.size - visible.size)
    }

    @Test
    fun showAll_returnsEveryChip() {
        val chips = (1..5).map { chip("e$it", ToolChipFamily.Explore) }
        assertEquals(chips, visibleStepParagraphChips(chips, showAll = true))
    }

    private fun chip(
        label: String,
        family: ToolChipFamily,
    ): SessionChatTimelineBlock.ToolChip =
        SessionChatTimelineBlock.ToolChip(
            kind = ToolChipKind.Command,
            family = family,
            label = label,
            status = ToolStatus.Completed,
            card =
                SessionChatTimelineBlock.ToolCard(
                    blockID = PresentationBlockId.direct(label),
                    toolName = label,
                    status = ToolStatus.Completed,
                    title = label,
                ),
        )
}
