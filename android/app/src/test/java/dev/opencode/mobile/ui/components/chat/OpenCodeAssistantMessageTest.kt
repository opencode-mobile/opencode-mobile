package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeAssistantMessageTest {
    @Test
    fun displayTextUsesPlaceholderWhenBlank() {
        assertEquals("…", assistantMessageDisplayText(""))
        assertEquals("…", assistantMessageDisplayText("   "))
    }

    @Test
    fun displayTextKeepsVisibleCopy() {
        assertEquals("Ready", assistantMessageDisplayText("Ready"))
    }
}
