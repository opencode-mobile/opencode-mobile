package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepParagraphChipsTest {
    // --- visibility ---

    @Test
    fun perFamilyLimits_changeCommandThree_othersUnlimited() {
        val chips =
            (1..2).map { chip("e$it", ToolChipFamily.Explore) } +
                (1..4).map { chip("c$it", ToolChipFamily.Change) } +
                (1..4).map { chip("x$it", ToolChipFamily.Command) } +
                (1..3).map { chip("a$it", ToolChipFamily.Agent) } +
                (1..4).map { chip("s$it", ToolChipFamily.Skill) }

        val result = StepParagraphChips.visibility(chips)

        assertEquals(
            listOf("e1", "e2", "c1", "c2", "c3", "x1", "x2", "x3", "a1", "a2", "a3", "s1", "s2", "s3", "s4"),
            result.visible.map { it.label },
        )
        assertEquals(2, result.hiddenCount)
    }

    @Test
    fun attentionChips_neverHidden_andNotCounted() {
        val chips =
            listOf(
                chip("x1", ToolChipFamily.Command, ToolStatus.Error),
                chip("x2", ToolChipFamily.Command, ToolStatus.Running),
                chip("x3", ToolChipFamily.Command),
                chip("x4", ToolChipFamily.Command, ToolStatus.Pending),
                chip("x5", ToolChipFamily.Command),
                chip("x6", ToolChipFamily.Command),
                chip("x7", ToolChipFamily.Command),
                chip("x8", ToolChipFamily.Command),
            )

        val result = StepParagraphChips.visibility(chips)

        // x3, x5 and x6 are the three counting Command chips; attention chips ride along in order.
        assertEquals(listOf("x1", "x2", "x3", "x4", "x5", "x6"), result.visible.map { it.label })
        assertEquals(2, result.hiddenCount)
    }

    @Test
    fun noParagraphCeiling_manyChipsAcrossFamiliesStayVisible() {
        val chips =
            (1..3).map { chip("c$it", ToolChipFamily.Change) } +
                (1..3).map { chip("x$it", ToolChipFamily.Command) } +
                (1..4).map { chip("a$it", ToolChipFamily.Agent) } +
                (1..4).map { chip("s$it", ToolChipFamily.Skill) }

        val result = StepParagraphChips.visibility(chips)

        assertEquals(chips, result.visible)
        assertEquals(0, result.hiddenCount)
    }

    @Test
    fun singleHiddenChip_showsEverythingInsteadOfPlusOneMore() {
        val chips = (1..4).map { chip("c$it", ToolChipFamily.Change) }

        val result = StepParagraphChips.visibility(chips)

        assertEquals(chips, result.visible)
        assertEquals(0, result.hiddenCount)
    }

    @Test
    fun hiddenCount_isTheSingleExpanderTotalAcrossFamilies() {
        val chips =
            (1..5).map { chip("c$it", ToolChipFamily.Change) } +
                (1..5).map { chip("x$it", ToolChipFamily.Command) }

        val result = StepParagraphChips.visibility(chips)

        assertEquals(listOf("c1", "c2", "c3", "x1", "x2", "x3"), result.visible.map { it.label })
        assertEquals(chips.size - result.visible.size, result.hiddenCount)
        assertEquals(4, result.hiddenCount)
    }

    @Test
    fun expandedThrough_showsChipsThatExistedAtTapTime_andCollapsesLaterArrivals() {
        val atTap = (1..5).map { chip("x$it", ToolChipFamily.Command, arrivalIndex = it - 1) }

        val expanded = StepParagraphChips.visibility(atTap, expandedThrough = atTap.size)
        assertEquals(atTap, expanded.visible)
        assertEquals(0, expanded.hiddenCount)

        // Five more commands arrive after the tap: the first five stay, the
        // new ones get their own limit of three and a fresh `+2 more`.
        val later = atTap + (6..10).map { chip("x$it", ToolChipFamily.Command, arrivalIndex = it - 1) }
        val result = StepParagraphChips.visibility(later, expandedThrough = atTap.size)
        assertEquals(listOf("x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8"), result.visible.map { it.label })
        assertEquals(2, result.hiddenCount)
    }

    @Test
    fun expandedThrough_countsByArrivalIndexNotRowPosition() {
        // Family order puts the later Change chip ahead of earlier Commands in
        // the row; expansion still follows arrival order.
        val chips =
            listOf(
                chip("c1", ToolChipFamily.Change, arrivalIndex = 4),
                chip("x1", ToolChipFamily.Command, arrivalIndex = 0),
                chip("x2", ToolChipFamily.Command, arrivalIndex = 1),
                chip("x3", ToolChipFamily.Command, arrivalIndex = 2),
                chip("x4", ToolChipFamily.Command, arrivalIndex = 3),
                chip("x5", ToolChipFamily.Command, arrivalIndex = 5),
                chip("x6", ToolChipFamily.Command, arrivalIndex = 6),
                chip("x7", ToolChipFamily.Command, arrivalIndex = 7),
                chip("x8", ToolChipFamily.Command, arrivalIndex = 8),
                chip("x9", ToolChipFamily.Command, arrivalIndex = 9),
            )

        val result = StepParagraphChips.visibility(chips, expandedThrough = 4)

        assertEquals(listOf("c1", "x1", "x2", "x3", "x4", "x5", "x6", "x7"), result.visible.map { it.label })
        assertEquals(2, result.hiddenCount)
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
        arrivalIndex: Int = 0,
    ): SessionChatTimelineBlock.ToolChip =
        SessionChatTimelineBlock.ToolChip(
            id = PresentationBlockId.direct(label),
            arrivalIndex = arrivalIndex,
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
