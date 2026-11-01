package dev.opencode.mobile.ui.components

import dev.opencode.mobile.domain.message.FollowUpDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionComposerSendLabelTest {
    @Test
    fun idleSession_isPlainSend() {
        assertEquals("Send", sessionComposerSendLabel(FollowUpDelivery.Steer, hasActiveWork = false))
        assertEquals("Send", sessionComposerSendLabel(FollowUpDelivery.Queue, hasActiveWork = false))
        assertEquals("Send", sessionComposerSendLabel(null, hasActiveWork = false))
    }

    @Test
    fun activeWork_namesTheFollowUpDelivery() {
        assertEquals("Send as Steer", sessionComposerSendLabel(FollowUpDelivery.Steer, hasActiveWork = true))
        assertEquals("Send as Queue", sessionComposerSendLabel(FollowUpDelivery.Queue, hasActiveWork = true))
        assertEquals("Send", sessionComposerSendLabel(null, hasActiveWork = true))
    }

    @Test
    fun sideQuestionMode_asksASideQuestionEvenWhileWorking() {
        assertEquals(
            "Ask side question",
            sessionComposerSendLabel(FollowUpDelivery.Steer, hasActiveWork = true, sideQuestionMode = true),
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
        assertEquals("Select one", questionSelectionHint(multiple = false))
        assertEquals("Select all that apply", questionSelectionHint(multiple = true))
        assertEquals("Select one", questionSelectionHint(multiple = true, maxSelections = 1))
        assertEquals("Select up to 3", questionSelectionHint(multiple = true, maxSelections = 3))
        assertEquals("Select at least 2", questionSelectionHint(multiple = true, minSelections = 2))
        assertEquals("Select 2 to 4", questionSelectionHint(multiple = true, minSelections = 2, maxSelections = 4))
        assertEquals("Select 2", questionSelectionHint(multiple = true, minSelections = 2, maxSelections = 2))
    }
}
