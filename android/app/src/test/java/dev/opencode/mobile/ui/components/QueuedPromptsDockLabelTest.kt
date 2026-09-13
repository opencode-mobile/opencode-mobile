package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuedPromptsDockLabelTest {
    @Test
    fun format_multipleItemsUsesCountAndFirstLine() {
        assertEquals(
            "3 queued · first line",
            formatQueuedPromptsDockLabel(3, "first line"),
        )
    }

    @Test
    fun format_collapsesNewlinesToSpaces() {
        assertEquals(
            "2 queued · first line second line",
            formatQueuedPromptsDockLabel(2, "first line\nsecond line"),
        )
    }

    @Test
    fun format_singleItem() {
        assertEquals(
            "1 queued · also check the compact path",
            formatQueuedPromptsDockLabel(1, "also check the compact path"),
        )
    }
}
