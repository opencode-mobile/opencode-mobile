package dev.opencode.mobile.ui.keyboard

import android.view.KeyEvent
import dev.opencode.mobile.ui.components.OpenCodeFocusedTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShortcutRulesTest {
    private fun match(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ) = KeyboardShortcutRules.match(keyCode, ctrl = ctrl, alt = alt, shift = shift, meta = meta)

    @Test
    fun match_mapsEachChordToItsAction() {
        assertEquals(KeyboardShortcutAction.ToggleSidebar, match(KeyEvent.KEYCODE_S, ctrl = true, alt = true))
        assertEquals(KeyboardShortcutAction.NextSession, match(KeyEvent.KEYCODE_TAB, ctrl = true))
        assertEquals(KeyboardShortcutAction.PreviousSession, match(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true))
        assertEquals(KeyboardShortcutAction.FocusComposer, match(KeyEvent.KEYCODE_L, ctrl = true))
    }

    @Test
    fun match_requiresExactModifiers() {
        assertNull(match(KeyEvent.KEYCODE_S, ctrl = true))
        assertNull(match(KeyEvent.KEYCODE_S, alt = true))
        assertNull(match(KeyEvent.KEYCODE_S, ctrl = true, alt = true, shift = true))
        assertNull(match(KeyEvent.KEYCODE_TAB))
        assertNull(match(KeyEvent.KEYCODE_TAB, shift = true))
        assertNull(match(KeyEvent.KEYCODE_TAB, ctrl = true, alt = true))
        assertNull(match(KeyEvent.KEYCODE_L))
        assertNull(match(KeyEvent.KEYCODE_L, ctrl = true, shift = true))
        assertNull(match(KeyEvent.KEYCODE_L, ctrl = true, alt = true))
    }

    @Test
    fun match_ignoresMetaChords() {
        assertNull(match(KeyEvent.KEYCODE_TAB, ctrl = true, meta = true))
        assertNull(match(KeyEvent.KEYCODE_L, ctrl = true, meta = true))
        assertNull(match(KeyEvent.KEYCODE_S, ctrl = true, alt = true, meta = true))
    }

    @Test
    fun match_ignoresOtherKeys() {
        assertNull(match(KeyEvent.KEYCODE_A, ctrl = true))
        assertNull(match(KeyEvent.KEYCODE_ENTER, ctrl = true))
    }

    @Test
    fun metaState_describesChordForShortcutHelper() {
        assertEquals(
            KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON,
            KeyboardShortcutAction.ToggleSidebar.metaState,
        )
        assertEquals(KeyEvent.META_CTRL_ON, KeyboardShortcutAction.NextSession.metaState)
        assertEquals(
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
            KeyboardShortcutAction.PreviousSession.metaState,
        )
        assertEquals(KeyEvent.META_CTRL_ON, KeyboardShortcutAction.FocusComposer.metaState)
    }

    @Test
    fun titles_matchIosLabels() {
        assertEquals(
            listOf("Toggle Sidebar", "Next Session", "Previous Session", "Focus Composer"),
            KeyboardShortcutAction.entries.map { it.title },
        )
    }

    @Test
    fun textFocus_mapsReportedInputs() {
        assertEquals(KeyboardShortcutTextFocus.None, KeyboardShortcutRules.textFocus(null, nativeTextEditorFocused = false))
        assertEquals(
            KeyboardShortcutTextFocus.Composer,
            KeyboardShortcutRules.textFocus(OpenCodeFocusedTextInput.SessionComposer, nativeTextEditorFocused = false),
        )
        assertEquals(
            KeyboardShortcutTextFocus.OtherTextField,
            KeyboardShortcutRules.textFocus(OpenCodeFocusedTextInput.Field, nativeTextEditorFocused = false),
        )
    }

    @Test
    fun textFocus_nativeEditorCountsAsOtherTextFieldWhenNoInputReports() {
        assertEquals(
            KeyboardShortcutTextFocus.OtherTextField,
            KeyboardShortcutRules.textFocus(null, nativeTextEditorFocused = true),
        )
    }

    @Test
    fun allowed_onlyBlocksOtherTextFieldsAndModalSurfaces() {
        assertTrue(KeyboardShortcutRules.allowed(KeyboardShortcutTextFocus.None, modalSurfaceShown = false))
        assertTrue(KeyboardShortcutRules.allowed(KeyboardShortcutTextFocus.Composer, modalSurfaceShown = false))
        assertFalse(KeyboardShortcutRules.allowed(KeyboardShortcutTextFocus.OtherTextField, modalSurfaceShown = false))
        assertFalse(KeyboardShortcutRules.allowed(KeyboardShortcutTextFocus.None, modalSurfaceShown = true))
        assertFalse(KeyboardShortcutRules.allowed(KeyboardShortcutTextFocus.Composer, modalSurfaceShown = true))
    }

    private val ids = listOf("a", "b", "c")

    private fun step(
        selected: String?,
        direction: SessionStepDirection,
        orderedIds: List<String> = ids,
    ) = KeyboardShortcutRules.adjacentSessionId(orderedIds, selected, direction)

    @Test
    fun adjacentSessionId_stepsThroughVisibleOrder() {
        assertEquals("b", step("a", SessionStepDirection.Next))
        assertEquals("c", step("b", SessionStepDirection.Next))
        assertEquals("b", step("c", SessionStepDirection.Previous))
        assertEquals("a", step("b", SessionStepDirection.Previous))
    }

    @Test
    fun adjacentSessionId_doesNotWrap() {
        assertNull(step("c", SessionStepDirection.Next))
        assertNull(step("a", SessionStepDirection.Previous))
    }

    @Test
    fun adjacentSessionId_noSelectionPicksFirstOrLast() {
        assertEquals("a", step(null, SessionStepDirection.Next))
        assertEquals("c", step(null, SessionStepDirection.Previous))
    }

    @Test
    fun adjacentSessionId_selectionOutsideVisibleListActsAsNoSelection() {
        assertEquals("a", step("hidden", SessionStepDirection.Next))
        assertEquals("c", step("hidden", SessionStepDirection.Previous))
    }

    @Test
    fun adjacentSessionId_emptyListDoesNothing() {
        assertNull(step(null, SessionStepDirection.Next, orderedIds = emptyList()))
        assertNull(step("a", SessionStepDirection.Previous, orderedIds = emptyList()))
    }

    @Test
    fun adjacentSessionId_singleSession() {
        assertEquals("a", step(null, SessionStepDirection.Next, orderedIds = listOf("a")))
        assertNull(step("a", SessionStepDirection.Next, orderedIds = listOf("a")))
        assertNull(step("a", SessionStepDirection.Previous, orderedIds = listOf("a")))
    }
}
