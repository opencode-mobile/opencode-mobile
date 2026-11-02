package dev.opencode.mobile.ui.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #869 Tier 2: digit picking and prompt history. */
class KeyboardPromptTier2RulesTest {
    private fun digit(
        keyCode: Int,
        inside: Boolean = true,
        textField: Boolean = false,
        options: Int = 3,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) = KeyboardNavigationRules.questionDigitPick(keyCode, ctrl, alt = false, shift = shift, meta = false, inside, textField, options)

    @Test
    fun digitsPickOptionsOnceFocusIsInside() {
        assertEquals(0, digit(KeyEvent.KEYCODE_1))
        assertEquals(2, digit(KeyEvent.KEYCODE_3))
        assertEquals(1, digit(KeyEvent.KEYCODE_NUMPAD_2))
        assertEquals(8, digit(KeyEvent.KEYCODE_9, options = 9))
    }

    @Test
    fun digitsDoNothingOutsideThePromptOrInATextField() {
        assertNull(digit(KeyEvent.KEYCODE_1, inside = false))
        assertNull(digit(KeyEvent.KEYCODE_1, textField = true))
    }

    @Test
    fun digitsPastTheOptionsOrWithModifiersDoNothing() {
        assertNull(digit(KeyEvent.KEYCODE_4, options = 3))
        assertNull(digit(KeyEvent.KEYCODE_0))
        assertNull(digit(KeyEvent.KEYCODE_1, ctrl = true))
        assertNull(digit(KeyEvent.KEYCODE_1, shift = true))
        assertNull(digit(KeyEvent.KEYCODE_A))
    }

    private val history = listOf("first", "second", "third")

    private fun recall(
        direction: PromptRecallDirection,
        browsing: Int? = null,
        unedited: Boolean = true,
        text: String = "",
        hasAttachments: Boolean = false,
        caretAtStart: Boolean = true,
        firstLine: Boolean = true,
        lastLine: Boolean = true,
        entries: List<String> = history,
    ) = KeyboardNavigationRules.promptRecall(
        history = entries,
        browsingIndex = browsing,
        unedited = unedited,
        text = text,
        hasAttachments = hasAttachments,
        caretAtStart = caretAtStart,
        caretOnFirstLine = firstLine,
        caretOnLastLine = lastLine,
        direction = direction,
    )

    @Test
    fun upInAnEmptyComposerShowsTheNewestPrompt() {
        assertEquals(PromptRecallStep(2, "third"), recall(PromptRecallDirection.Older))
    }

    @Test
    fun upKeepsSteppingBackAndStopsAtTheOldest() {
        assertEquals(PromptRecallStep(1, "second"), recall(PromptRecallDirection.Older, browsing = 2, text = "third"))
        assertEquals(PromptRecallStep(0, "first"), recall(PromptRecallDirection.Older, browsing = 1, text = "second"))
        assertEquals(PromptRecallStep(0, "first"), recall(PromptRecallDirection.Older, browsing = 0, text = "first"))
    }

    @Test
    fun downStepsForwardAndEndsEmpty() {
        assertEquals(PromptRecallStep(2, "third"), recall(PromptRecallDirection.Newer, browsing = 1, text = "second"))
        assertEquals(PromptRecallStep(null, ""), recall(PromptRecallDirection.Newer, browsing = 2, text = "third"))
    }

    @Test
    fun recallNeedsAnEmptyComposerWithTheCaretAtTheStart() {
        assertNull(recall(PromptRecallDirection.Older, text = "draft"))
        assertNull(recall(PromptRecallDirection.Older, caretAtStart = false))
        assertNull(recall(PromptRecallDirection.Older, entries = emptyList()))
        // Down does nothing unless browsing.
        assertNull(recall(PromptRecallDirection.Newer))
    }

    @Test
    fun aDraftWithAttachmentsIsNotEmpty() {
        assertNull(recall(PromptRecallDirection.Older, hasAttachments = true))
    }

    @Test
    fun inAMultiLinePromptUpStepsOnlyFromTheFirstLine() {
        // Caret on a later line: ↑ moves the caret instead.
        assertNull(recall(PromptRecallDirection.Older, browsing = 2, text = "third", firstLine = false, lastLine = true))
        assertEquals(
            PromptRecallStep(1, "second"),
            recall(PromptRecallDirection.Older, browsing = 2, text = "third", firstLine = true, lastLine = false),
        )
    }

    @Test
    fun inAMultiLinePromptDownStepsOnlyFromTheLastLine() {
        assertNull(recall(PromptRecallDirection.Newer, browsing = 1, text = "second", firstLine = true, lastLine = false))
        assertEquals(
            PromptRecallStep(2, "third"),
            recall(PromptRecallDirection.Newer, browsing = 1, text = "second", firstLine = false, lastLine = true),
        )
    }

    @Test
    fun anEditEndsBrowsing() {
        assertNull(recall(PromptRecallDirection.Older, browsing = 2, unedited = false, text = "third!"))
        assertNull(recall(PromptRecallDirection.Newer, browsing = 1, unedited = false, text = "second?"))
    }
}
