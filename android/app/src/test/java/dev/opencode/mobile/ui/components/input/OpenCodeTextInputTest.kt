package dev.opencode.mobile.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

    @Test fun clipKeepsTextUnderTheLimit() {
        val value = TextFieldValue("hello", selection = TextRange(5))
        assertEquals(value, clipOpenCodeTextInput(value, 5))
        assertEquals(value, clipOpenCodeTextInput(value, null))
    }

    @Test fun clipCutsPastedTextAndKeepsTheCursorInside() {
        val pasted = TextFieldValue("abcdefgh", selection = TextRange(8))
        val clipped = clipOpenCodeTextInput(pasted, 5)
        assertEquals("abcde", clipped.text)
        assertEquals(TextRange(5), clipped.selection)
    }

    @Test fun lengthLabelShowsFromNinetyPercent() {
        assertNull(openCodeTextInputLengthLabel(4_499, 5_000))
        assertEquals("4500/5000", openCodeTextInputLengthLabel(4_500, 5_000))
        assertEquals("5000/5000", openCodeTextInputLengthLabel(5_000, 5_000))
        assertNull(openCodeTextInputLengthLabel(10, null))
    }
}
