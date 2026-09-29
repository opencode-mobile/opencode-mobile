package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeToolChipTest {
    @Test
    fun accessibilityLabel_fileWithCounts() {
        assertEquals(
            "file Navigation.kt, +4 −1, completed",
            toolChipAccessibilityLabel(
                OpenCodeToolChipVariant.File("Navigation.kt", additions = 4, deletions = 1),
                OpenCodeToolChipStatus.Completed,
                trailingText = null,
            ),
        )
    }

    @Test
    fun accessibilityLabel_commandWithTrailing() {
        assertEquals(
            "command xcodebuild test, error, 42s",
            toolChipAccessibilityLabel(
                OpenCodeToolChipVariant.Command("xcodebuild test"),
                OpenCodeToolChipStatus.Error,
                trailingText = "42s",
            ),
        )
    }

    @Test
    fun accessibilityLabel_agentWithBrief() {
        assertEquals(
            "agent Explore Find the pill, running",
            toolChipAccessibilityLabel(
                OpenCodeToolChipVariant.Agent("Explore", "Find the pill"),
                OpenCodeToolChipStatus.Running,
                trailingText = null,
            ),
        )
    }

    @Test
    fun accessibilityLabel_moreIgnoresStatus() {
        assertEquals(
            "3 more tools",
            toolChipAccessibilityLabel(
                OpenCodeToolChipVariant.More(3),
                OpenCodeToolChipStatus.Error,
                trailingText = null,
            ),
        )
    }
}
