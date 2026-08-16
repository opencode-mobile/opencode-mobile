package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeChildSessionRowTest {
    @Test
    fun letterUsesFirstUppercasedCharacter() {
        assertEquals("E", childSessionRowLetter(from = "explore"))
        assertEquals("T", childSessionRowLetter(from = "Task"))
        assertEquals("?", childSessionRowLetter(from = ""))
    }

    @Test
    fun accessibilityLabelIncludesTitleCaptionSubtitleAndElapsed() {
        val label =
            buildChildSessionRowAccessibilityLabel(
                title = "explore",
                caption = "background",
                subtitle = "Find auth bugs",
                elapsed = "1:04",
            )

        assertEquals("explore, background, Find auth bugs, 1:04", label)
    }

    @Test
    fun accessibilityLabelUsesTitleOnlyWhenNoOptionalContent() {
        val label =
            buildChildSessionRowAccessibilityLabel(
                title = "Task",
                caption = "",
                subtitle = null,
                elapsed = "",
            )

        assertEquals("Task", label)
    }
}
