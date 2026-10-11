package dev.opencode.mobile.ui.keyboard

import android.view.KeyEvent
import dev.opencode.mobile.ui.components.OpenCodeFocusedTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShortcutHostTest {
    private val host = KeyboardShortcutHost()
    private val calls = mutableListOf<String>()

    private fun press(
        keyCode: Int,
        ctrl: Boolean = true,
        alt: Boolean = false,
        shift: Boolean = false,
        repeatCount: Int = 0,
        nativeTextEditorFocused: Boolean = false,
    ): Boolean =
        host.handleKey(
            isDown = true,
            keyCode = keyCode,
            ctrl = ctrl,
            alt = alt,
            shift = shift,
            meta = false,
            repeatCount = repeatCount,
            nativeTextEditorFocused = nativeTextEditorFocused,
        )

    private fun release(keyCode: Int): Boolean =
        host.handleKey(
            isDown = false,
            keyCode = keyCode,
            ctrl = true,
            alt = false,
            shift = false,
            meta = false,
            repeatCount = 0,
            nativeTextEditorFocused = false,
        )

    @Test
    fun unregisteredShortcutIsNotConsumed() {
        assertFalse(press(KeyEvent.KEYCODE_L))
        assertFalse(release(KeyEvent.KEYCODE_L))
    }

    @Test
    fun registeredShortcutRunsAndConsumesDownAndUp() {
        host.register(KeyboardShortcutAction.FocusComposer) { calls += "focus" }
        assertTrue(press(KeyEvent.KEYCODE_L))
        assertTrue(release(KeyEvent.KEYCODE_L))
        assertEquals(listOf("focus"), calls)
        assertFalse(release(KeyEvent.KEYCODE_L))
    }

    @Test
    fun newestRegistrationWins_andUnregisterRestoresPrevious() {
        host.register(KeyboardShortcutAction.FocusComposer) { calls += "home" }
        val pushed = host.register(KeyboardShortcutAction.FocusComposer) { calls += "pushed" }
        press(KeyEvent.KEYCODE_L)
        host.unregister(pushed)
        press(KeyEvent.KEYCODE_L)
        assertEquals(listOf("pushed", "home"), calls)
    }

    @Test
    fun otherTextFieldFocusLetsKeyThrough() {
        host.register(KeyboardShortcutAction.NextSession) { calls += "next" }
        val field = Any()
        host.inputFocus.updateTextInput(field, OpenCodeFocusedTextInput.Field, focused = true)
        assertFalse(press(KeyEvent.KEYCODE_TAB))
        host.inputFocus.updateTextInput(field, OpenCodeFocusedTextInput.Field, focused = false)
        assertTrue(press(KeyEvent.KEYCODE_TAB))
        assertEquals(listOf("next"), calls)
    }

    @Test
    fun composerFocusKeepsShortcutsActive() {
        host.register(KeyboardShortcutAction.ToggleSidebar) { calls += "toggle" }
        host.inputFocus.updateTextInput(Any(), OpenCodeFocusedTextInput.SessionComposer, focused = true)
        assertTrue(press(KeyEvent.KEYCODE_S, alt = true))
        assertEquals(listOf("toggle"), calls)
    }

    @Test
    fun removedFieldNoLongerBlocks() {
        host.register(KeyboardShortcutAction.FocusComposer) { calls += "focus" }
        val field = Any()
        host.inputFocus.updateTextInput(field, OpenCodeFocusedTextInput.Field, focused = true)
        host.inputFocus.removeTextInput(field)
        assertTrue(press(KeyEvent.KEYCODE_L))
    }

    @Test
    fun nativeTextEditorFocusLetsKeyThrough() {
        host.register(KeyboardShortcutAction.FocusComposer) { calls += "focus" }
        assertFalse(press(KeyEvent.KEYCODE_L, nativeTextEditorFocused = true))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun modalSurfaceBlocksShortcuts() {
        host.register(KeyboardShortcutAction.PreviousSession) { calls += "previous" }
        val sheet = Any()
        host.inputFocus.updateModalSurface(sheet, shown = true)
        assertFalse(press(KeyEvent.KEYCODE_TAB, shift = true))
        host.inputFocus.updateModalSurface(sheet, shown = false)
        assertTrue(press(KeyEvent.KEYCODE_TAB, shift = true))
        assertEquals(listOf("previous"), calls)
    }

    @Test
    fun heldChordRepeatsSessionSteppingOnly() {
        host.register(KeyboardShortcutAction.NextSession) { calls += "next" }
        host.register(KeyboardShortcutAction.ToggleSidebar) { calls += "toggle" }
        assertTrue(press(KeyEvent.KEYCODE_TAB, repeatCount = 0))
        assertTrue(press(KeyEvent.KEYCODE_TAB, repeatCount = 1))
        assertTrue(press(KeyEvent.KEYCODE_S, alt = true, repeatCount = 0))
        assertTrue(press(KeyEvent.KEYCODE_S, alt = true, repeatCount = 1))
        assertEquals(listOf("next", "next", "toggle"), calls)
    }

    @Test
    fun activeActionsListsRegisteredShortcutsInDeclarationOrder() {
        assertEquals(emptyList<KeyboardShortcutAction>(), host.activeActions())
        host.register(KeyboardShortcutAction.FocusComposer) {}
        val toggle = host.register(KeyboardShortcutAction.ToggleSidebar) {}
        host.register(KeyboardShortcutAction.ToggleSidebar) {}
        assertEquals(
            listOf(KeyboardShortcutAction.ToggleSidebar, KeyboardShortcutAction.FocusComposer),
            host.activeActions(),
        )
        host.unregister(toggle)
        assertEquals(
            listOf(KeyboardShortcutAction.ToggleSidebar, KeyboardShortcutAction.FocusComposer),
            host.activeActions(),
        )
    }
}
