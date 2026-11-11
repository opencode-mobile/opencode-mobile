package dev.opencode.mobile.ui.components

import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.ui.text.EnglishStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionComposerSendLabelTest {
    @Test
    fun idleSession_isPlainSend() {
        assertEquals("Send", sessionComposerSendLabel(FollowUpDelivery.Steer, hasActiveWork = false).resolve(EnglishStrings))
        assertEquals("Send", sessionComposerSendLabel(FollowUpDelivery.Queue, hasActiveWork = false).resolve(EnglishStrings))
        assertEquals("Send", sessionComposerSendLabel(null, hasActiveWork = false).resolve(EnglishStrings))
    }

    @Test
    fun activeWork_namesTheFollowUpDelivery() {
        assertEquals("Send as Steer", sessionComposerSendLabel(FollowUpDelivery.Steer, hasActiveWork = true).resolve(EnglishStrings))
        assertEquals("Send as Queue", sessionComposerSendLabel(FollowUpDelivery.Queue, hasActiveWork = true).resolve(EnglishStrings))
        assertEquals("Send", sessionComposerSendLabel(null, hasActiveWork = true).resolve(EnglishStrings))
    }

    @Test
    fun sideQuestionMode_asksASideQuestionEvenWhileWorking() {
        assertEquals(
            "Ask side question",
            sessionComposerSendLabel(FollowUpDelivery.Steer, hasActiveWork = true, sideQuestionMode = true).resolve(EnglishStrings),
        )
    }

    @Test
    fun rebase_keepsKeystrokesTypedOntoADroppedPrefix() {
        assertEquals("W", rebaseAppendedComposerInput(shown = "/btw ", typed = "/btw W", current = ""))
        assertEquals("Why", rebaseAppendedComposerInput(shown = "/btw Wh", typed = "/btw Why", current = "Wh"))
    }

    @Test
    fun typedOntoDroppedPrefix_acceptsNewTypingButNotAStaleEcho() {
        assertTrue(typedOntoDroppedComposerPrefix(shown = "/btw ", typed = "W", current = ""))
        assertFalse(typedOntoDroppedComposerPrefix(shown = "/btw ", typed = "/btw ", current = ""))
        assertFalse(typedOntoDroppedComposerPrefix(shown = "@fi", typed = "@fil", current = "@file.ts "))
        assertFalse(typedOntoDroppedComposerPrefix(shown = "ab", typed = "abc", current = "ab"))
    }

    @Test
    fun rebase_leavesOrdinaryEditsAlone() {
        assertNull(rebaseAppendedComposerInput(shown = "ab", typed = "abc", current = "ab"))
        assertNull(rebaseAppendedComposerInput(shown = "@fi", typed = "@fil", current = "@file.ts "))
        assertNull(rebaseAppendedComposerInput(shown = "/btw ", typed = "W", current = ""))
        assertNull(rebaseAppendedComposerInput(shown = "/btw W", typed = "/btw ", current = "W"))
    }

    @Test
    fun questionSelectionHint_reflectsFormBounds() {
        assertEquals("Select one", questionSelectionHint(multiple = false).resolve(EnglishStrings))
        assertEquals("Select all that apply", questionSelectionHint(multiple = true).resolve(EnglishStrings))
        assertEquals("Select one", questionSelectionHint(multiple = true, maxSelections = 1).resolve(EnglishStrings))
        assertEquals("Select up to 3", questionSelectionHint(multiple = true, maxSelections = 3).resolve(EnglishStrings))
        assertEquals("Select at least 2", questionSelectionHint(multiple = true, minSelections = 2).resolve(EnglishStrings))
        assertEquals("Select 2 to 4", questionSelectionHint(multiple = true, minSelections = 2, maxSelections = 4).resolve(EnglishStrings))
        assertEquals("Select 2", questionSelectionHint(multiple = true, minSelections = 2, maxSelections = 2).resolve(EnglishStrings))
    }
}
