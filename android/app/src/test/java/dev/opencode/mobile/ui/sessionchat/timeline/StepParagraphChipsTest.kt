package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepParagraphChipsTest {
    // --- visibility ---

    @Test
    fun perFamilyLimits_changeCommandThree_exploreAgentTwo_skillUnlimited() {
        val chips =
            (1..4).map { chip("s$it", ToolChipFamily.Skill) } +
                (1..3).map { chip("e$it", ToolChipFamily.Explore) } +
                (1..3).map { chip("a$it", ToolChipFamily.Agent) } +
                (1..4).map { chip("c$it", ToolChipFamily.Change) } +
                (1..4).map { chip("x$it", ToolChipFamily.Command) }

        val result = StepParagraphChips.visibility(chips, expanded = false)

        // Counting chips after family limits: 4 + 2 + 2 + 3 + 3 = 14 > 8; the
        // ceiling can only trim the two remaining Explore chips.
        assertEquals(
            listOf("s1", "s2", "s3", "s4", "a1", "a2", "c1", "c2", "c3", "x1", "x2", "x3"),
            result.visible.map { it.label },
        )
        assertEquals(6, result.hiddenCount)
    }

    @Test
    fun withinCeiling_onlyFamilyLimitsApply() {
        val chips =
            (1..3).map { chip("e$it", ToolChipFamily.Explore) } +
                (1..4).map { chip("c$it", ToolChipFamily.Change) }

        val result = StepParagraphChips.visibility(chips, expanded = false)

        assertEquals(listOf("e1", "e2", "c1", "c2", "c3"), result.visible.map { it.label })
        assertEquals(2, result.hiddenCount)
    }

    @Test
    fun attentionChips_neverHidden_andNotCounted() {
        val chips =
            listOf(
                chip("e1", ToolChipFamily.Explore, ToolStatus.Error),
                chip("e2", ToolChipFamily.Explore, ToolStatus.Running),
                chip("e3", ToolChipFamily.Explore),
                chip("e4", ToolChipFamily.Explore, ToolStatus.Pending),
                chip("e5", ToolChipFamily.Explore),
                chip("e6", ToolChipFamily.Explore),
                chip("e7", ToolChipFamily.Explore),
            )

        val result = StepParagraphChips.visibility(chips, expanded = false)

        // e3 and e5 are the first two counting Explore chips; attention chips ride along in order.
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), result.visible.map { it.label })
        assertEquals(2, result.hiddenCount)
    }

    @Test
    fun ceiling_trimsExploreFromTheEnd_andNeverOtherFamilies() {
        val chips =
            (1..2).map { chip("e$it", ToolChipFamily.Explore) } +
                (1..2).map { chip("a$it", ToolChipFamily.Agent) } +
                (1..3).map { chip("c$it", ToolChipFamily.Change) } +
                (1..3).map { chip("x$it", ToolChipFamily.Command) }

        val result = StepParagraphChips.visibility(chips, expanded = false)

        // 10 counting chips: both Explore chips go (from the end) to meet the ceiling of 8.
        assertEquals(listOf("a1", "a2", "c1", "c2", "c3", "x1", "x2", "x3"), result.visible.map { it.label })
        assertEquals(2, result.hiddenCount)
    }

    @Test
    fun ceiling_trimsOnlyAsManyExploreAsNeeded_fromTheEnd() {
        val chips =
            (1..2).map { chip("e$it", ToolChipFamily.Explore) } +
                (1..2).map { chip("a$it", ToolChipFamily.Agent) } +
                (1..3).map { chip("c$it", ToolChipFamily.Change) } +
                (1..2).map { chip("x$it", ToolChipFamily.Command) } +
                chip("x3", ToolChipFamily.Command, ToolStatus.Error)

        val result = StepParagraphChips.visibility(chips, expanded = false)

        // 9 counting chips (x3 needs attention): only e2 is trimmed, but a lone
        // hidden chip is shown instead of `+1 more`.
        assertEquals(chips, result.visible)
        assertEquals(0, result.hiddenCount)
    }

    @Test
    fun ceiling_cannotBeMetWithoutExplore_leavesOtherFamiliesVisible() {
        val chips = (1..10).map { chip("s$it", ToolChipFamily.Skill) }

        val result = StepParagraphChips.visibility(chips, expanded = false)

        assertEquals(chips, result.visible)
        assertEquals(0, result.hiddenCount)
    }

    @Test
    fun singleHiddenChip_showsEverythingInsteadOfPlusOneMore() {
        val chips = (1..3).map { chip("e$it", ToolChipFamily.Explore) }

        val result = StepParagraphChips.visibility(chips, expanded = false)

        assertEquals(chips, result.visible)
        assertEquals(0, result.hiddenCount)
    }

    @Test
    fun hiddenCount_isTheSingleExpanderTotalAcrossFamilies() {
        val chips =
            (1..4).map { chip("e$it", ToolChipFamily.Explore) } +
                (1..5).map { chip("x$it", ToolChipFamily.Command) }

        val result = StepParagraphChips.visibility(chips, expanded = false)

        assertEquals(listOf("e1", "e2", "x1", "x2", "x3"), result.visible.map { it.label })
        assertEquals(chips.size - result.visible.size, result.hiddenCount)
        assertEquals(4, result.hiddenCount)
    }

    @Test
    fun expanded_showsEveryChip() {
        val chips = (1..5).map { chip("e$it", ToolChipFamily.Explore) }

        val result = StepParagraphChips.visibility(chips, expanded = true)

        assertEquals(chips, result.visible)
        assertEquals(0, result.hiddenCount)
    }

    // --- repeat marker ---

    @Test
    fun displayTrailingText_prefixesRepeatCount() {
        assertNull(chip("x", ToolChipFamily.Command).displayTrailingText)
        assertEquals("×3", chip("x", ToolChipFamily.Command).copy(repeatCount = 3).displayTrailingText)
        assertEquals(
            "×2 · 42s",
            chip("x", ToolChipFamily.Command).copy(repeatCount = 2, trailingText = "42s").displayTrailingText,
        )
        assertEquals("42s", chip("x", ToolChipFamily.Command).copy(trailingText = "42s").displayTrailingText)
    }

    // --- command label ---

    @Test
    fun commandLabel_stripsLeadingCdSegments() {
        assertEquals("./gradlew test", StepParagraphChips.commandLabel("cd /repo/android && ./gradlew test"))
        assertEquals("ls", StepParagraphChips.commandLabel("cd repo; ls"))
        assertEquals("make", StepParagraphChips.commandLabel("cd \"a b\" && cd sub && make"))
    }

    @Test
    fun commandLabel_stripsExportsAndAssignmentPrefixes() {
        assertEquals("./gradlew test", StepParagraphChips.commandLabel("export JAVA_HOME=/opt/jdk && ./gradlew test"))
        assertEquals("npm test", StepParagraphChips.commandLabel("export A=1 B='x y'; npm test"))
        assertEquals("npm test", StepParagraphChips.commandLabel("CI=1 NODE_ENV=\"test env\" npm test"))
        assertEquals("export FOO && make", StepParagraphChips.commandLabel("export FOO && make"))
        assertEquals(
            "./gradlew assemble",
            StepParagraphChips.commandLabel("cd android && export JAVA_HOME=/jdk && JAVA_OPTS=-Xmx2g ./gradlew assemble"),
        )
    }

    @Test
    fun commandLabel_keepsOriginalWhenStrippingLeavesNothing() {
        assertEquals("cd /repo &&", StepParagraphChips.commandLabel("cd /repo &&"))
        assertEquals("FOO=bar", StepParagraphChips.commandLabel("FOO=bar"))
        assertEquals("cd /repo", StepParagraphChips.commandLabel("cd /repo"))
    }

    @Test
    fun commandLabel_firstLineWhitespaceCollapsed() {
        assertEquals("gh pr list --state open", StepParagraphChips.commandLabel("\n\n  gh   pr list \t--state open\nls"))
    }

    @Test
    fun commandLabel_truncatesAtLastWhitespaceBefore48() {
        val command = "git log --oneline --graph --decorate --all --since=yesterday"
        val label = StepParagraphChips.commandLabel(command)
        assertEquals("git log --oneline --graph --decorate --all…", label)
        assertEquals(command.take(48).substringBeforeLast(' ') + "…", label)
        val exactly48 = "a".repeat(20) + " " + "b".repeat(27)
        assertEquals(48, exactly48.length)
        assertEquals(exactly48, StepParagraphChips.commandLabel(exactly48))
    }

    @Test
    fun commandLabel_neverCutsInsideTheFirstToken() {
        val longToken = "/very/long/path/" + "x".repeat(50) + "/bin/tool"
        assertEquals(longToken + "…", StepParagraphChips.commandLabel("$longToken --flag value"))
        assertEquals(longToken, StepParagraphChips.commandLabel(longToken))
        val sixty = "echo " + "x".repeat(55)
        assertEquals("echo…", StepParagraphChips.commandLabel(sixty))
    }

    private fun chip(
        label: String,
        family: ToolChipFamily,
        status: ToolStatus = ToolStatus.Completed,
    ): SessionChatTimelineBlock.ToolChip =
        SessionChatTimelineBlock.ToolChip(
            kind = ToolChipKind.Command,
            family = family,
            label = label,
            status = status,
            card =
                SessionChatTimelineBlock.ToolCard(
                    blockID = PresentationBlockId.direct(label),
                    toolName = label,
                    status = status,
                    title = label,
                ),
        )
}
