package dev.opencode.mobile.ui.components.list

import dev.opencode.mobile.ui.components.OPEN_CODE_TAG_ACCENT_FILL_ALPHA
import dev.opencode.mobile.ui.components.OpenCodeTagTone
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

    @Test
    fun tagTone_defaultsToNeutralWithAnAccentAlternative() {
        assertEquals(listOf(OpenCodeTagTone.Neutral, OpenCodeTagTone.Accent), OpenCodeTagTone.entries)
        assertEquals(0.12f, OPEN_CODE_TAG_ACCENT_FILL_ALPHA, 0.0001f)
    }
}
