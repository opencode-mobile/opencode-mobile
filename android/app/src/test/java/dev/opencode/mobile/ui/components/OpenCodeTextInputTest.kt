package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeTextInputTest {
    @Test fun accessibilityLabelPrefersExplicitValueThenVisibleLabel() {
        assertEquals("Filter changed files", resolveOpenCodeTextInputAccessibilityLabel(null, "Filter changed files"))
        assertEquals("Server URL", resolveOpenCodeTextInputAccessibilityLabel("Server URL", null))
        assertEquals("Explicit", resolveOpenCodeTextInputAccessibilityLabel("Visible", "Explicit"))
    }

    @Test fun placeholderDoesNotSatisfyAccessibilityContract() {
        assertNull(resolveOpenCodeTextInputAccessibilityLabel(null, null))
        assertNull(resolveOpenCodeTextInputAccessibilityLabel("  ", "\n"))
    }
}
