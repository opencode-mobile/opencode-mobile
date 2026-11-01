package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun fillThresholdIsEightyPercent() {
        assertEquals(0.8f, ASSISTANT_MESSAGE_FILL_ROW_THRESHOLD)
    }

    @Test
    fun narrowContentHugs() {
        assertFalse(assistantMessageFillsRow(contentWidth = 60f, availableWidth = 358f))
        assertFalse(assistantMessageFillsRow(contentWidth = 250f, availableWidth = 358f))
    }

    @Test
    fun contentAtExactlyEightyPercentStillHugs() {
        assertFalse(assistantMessageFillsRow(contentWidth = 80f, availableWidth = 100f))
    }

    @Test
    fun contentPastEightyPercentFillsRow() {
        assertTrue(assistantMessageFillsRow(contentWidth = 80.5f, availableWidth = 100f))
        assertTrue(assistantMessageFillsRow(contentWidth = 340f, availableWidth = 358f))
        assertTrue(assistantMessageFillsRow(contentWidth = 358f, availableWidth = 358f))
    }

    @Test
    fun zeroOrUnboundedAvailableWidthNeverFills() {
        assertFalse(assistantMessageFillsRow(contentWidth = 0f, availableWidth = 0f))
        assertFalse(assistantMessageFillsRow(contentWidth = 10f, availableWidth = 0f))
        assertFalse(assistantMessageFillsRow(contentWidth = 10f, availableWidth = Float.POSITIVE_INFINITY))
    }
}
