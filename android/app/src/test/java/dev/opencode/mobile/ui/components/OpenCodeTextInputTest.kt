package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeTextInputTest {
    @Test fun accessibilityLabelTakesPrecedenceOverHint() {
        assertEquals("Filter changed files", resolveOpenCodeTextInputAccessibilityLabel("Filter", "Filter changed files"))
        assertEquals("Server URL", resolveOpenCodeTextInputAccessibilityLabel("Server URL", null))
        assertEquals("Explicit", resolveOpenCodeTextInputAccessibilityLabel("Visible hint", "Explicit"))
    }

    @Test fun blankHintAndAccessibilityLabelFailContract() {
        assertNull(resolveOpenCodeTextInputAccessibilityLabel(null, null))
        assertNull(resolveOpenCodeTextInputAccessibilityLabel("  ", "\n"))
        assertNull(resolveOpenCodeTextInputAccessibilityLabel("", null))
    }
}
