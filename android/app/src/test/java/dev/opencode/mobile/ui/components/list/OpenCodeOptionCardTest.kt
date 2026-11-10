package dev.opencode.mobile.ui.components.list

import dev.opencode.mobile.ui.components.openCodeOptionCardAccessibilityLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeOptionCardTest {
    @Test
    fun accessibilityLabel_readsTitleSubtitleThenTag() {
        assertEquals(
            "Activity Card, Running steps on one live card, Recommended",
            openCodeOptionCardAccessibilityLabel("Activity Card", "Running steps on one live card", "Recommended"),
        )
    }

    @Test
    fun accessibilityLabel_skipsMissingParts() {
        assertEquals("Standard", openCodeOptionCardAccessibilityLabel("Standard", null, null))
        assertEquals("Standard, Current", openCodeOptionCardAccessibilityLabel("Standard", " ", "Current"))
        assertEquals("Step Rail, One line per step", openCodeOptionCardAccessibilityLabel("Step Rail", "One line per step", ""))
    }
}
