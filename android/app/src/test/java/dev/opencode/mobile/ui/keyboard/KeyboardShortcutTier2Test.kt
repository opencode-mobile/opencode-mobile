package dev.opencode.mobile.ui.keyboard

import android.view.KeyEvent
import dev.opencode.mobile.ui.components.OpenCodeFocusedTextInput
import dev.opencode.mobile.ui.components.OpenCodeInputFocusRegistry
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #869 Tier 2: chord map, gating matrix, helper groups. */
class KeyboardShortcutTier2Test {
    private fun match(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ) = KeyboardShortcutRules.match(keyCode, ctrl = ctrl, alt = alt, shift = shift, meta = meta)

    @Test
    fun everyTier2ChordMatchesItsAction() {
        assertEquals(KeyboardShortcutAction.NewSession, match(KeyEvent.KEYCODE_N, ctrl = true))
        assertEquals(KeyboardShortcutAction.CommandPalette, match(KeyEvent.KEYCODE_K, ctrl = true))
        assertEquals(KeyboardShortcutAction.ModelPicker, match(KeyEvent.KEYCODE_M, ctrl = true, shift = true))
        assertEquals(KeyboardShortcutAction.Settings, match(KeyEvent.KEYCODE_COMMA, ctrl = true))
        assertEquals(KeyboardShortcutAction.PromptPrimary, match(KeyEvent.KEYCODE_ENTER, ctrl = true))
        assertEquals(KeyboardShortcutAction.PromptPrimary, match(KeyEvent.KEYCODE_NUMPAD_ENTER, ctrl = true))
        assertEquals(KeyboardShortcutAction.PromptAlways, match(KeyEvent.KEYCODE_ENTER, ctrl = true, alt = true))
        assertEquals(KeyboardShortcutAction.PromptReject, match(KeyEvent.KEYCODE_DEL, ctrl = true))
        val digits =
            listOf(
                KeyEvent.KEYCODE_1,
                KeyEvent.KEYCODE_2,
                KeyEvent.KEYCODE_3,
                KeyEvent.KEYCODE_4,
                KeyEvent.KEYCODE_5,
                KeyEvent.KEYCODE_6,
                KeyEvent.KEYCODE_7,
                KeyEvent.KEYCODE_8,
                KeyEvent.KEYCODE_9,
            )
        digits.forEachIndexed { index, keyCode ->
            assertEquals(KeyboardShortcutAction.projects[index], match(keyCode, ctrl = true))
        }
    }

    @Test
    fun tier2ChordsNeedExactModifiers() {
        assertNull(match(KeyEvent.KEYCODE_N))
        assertNull(match(KeyEvent.KEYCODE_N, ctrl = true, shift = true))
        assertNull(match(KeyEvent.KEYCODE_K, ctrl = true, alt = true))
        // Ctrl+M (no Shift) is not the model picker.
        assertNull(match(KeyEvent.KEYCODE_M, ctrl = true))
        assertNull(match(KeyEvent.KEYCODE_COMMA, ctrl = true, shift = true))
        // Plain Return, Shift+Return and Ctrl+Shift+Return stay with the text field.
        assertNull(match(KeyEvent.KEYCODE_ENTER))
        assertNull(match(KeyEvent.KEYCODE_ENTER, shift = true))
        assertNull(match(KeyEvent.KEYCODE_ENTER, ctrl = true, shift = true))
        assertNull(match(KeyEvent.KEYCODE_DEL))
        assertNull(match(KeyEvent.KEYCODE_DEL, ctrl = true, alt = true))
        // Bare digits are not chords; Ctrl+0 is unbound.
        assertNull(match(KeyEvent.KEYCODE_1))
        assertNull(match(KeyEvent.KEYCODE_0, ctrl = true))
        assertNull(match(KeyEvent.KEYCODE_1, ctrl = true, shift = true))
        // Meta (Search / Windows / Command) is the system's.
        assertNull(match(KeyEvent.KEYCODE_N, ctrl = true, meta = true))
        assertNull(match(KeyEvent.KEYCODE_ENTER, ctrl = true, meta = true))
    }

    @Test
    fun everyChordIsUnique() {
        val chords = KeyboardShortcutAction.entries.map { listOf(it.keyCode, it.metaState) }
        assertEquals(chords.size, chords.toSet().size)
    }

    @Test
    fun gatingMatrix() {
        val prompt = listOf(KeyboardShortcutAction.PromptPrimary, KeyboardShortcutAction.PromptAlways, KeyboardShortcutAction.PromptReject)
        val app = KeyboardShortcutAction.entries - prompt.toSet()
        for (action in KeyboardShortcutAction.entries) {
            // Nothing over a sheet, dialog or menu.
            for (focus in KeyboardShortcutTextFocus.entries) {
                assertFalse("$action over a sheet", KeyboardShortcutRules.allowed(action, focus, modalSurfaceShown = true))
            }
            // Never while another text field has focus.
            assertFalse(KeyboardShortcutRules.allowed(action, KeyboardShortcutTextFocus.OtherTextField, false))
            // With nothing focused, or from the composer.
            assertTrue(KeyboardShortcutRules.allowed(action, KeyboardShortcutTextFocus.None, false))
            assertTrue(KeyboardShortcutRules.allowed(action, KeyboardShortcutTextFocus.Composer, false))
        }
        // "Type your own answer": only Ctrl+Enter (save and Next / Submit).
        assertTrue(KeyboardShortcutRules.allowed(KeyboardShortcutAction.PromptPrimary, KeyboardShortcutTextFocus.CustomAnswer, false))
        assertFalse(KeyboardShortcutRules.allowed(KeyboardShortcutAction.PromptReject, KeyboardShortcutTextFocus.CustomAnswer, false))
        assertFalse(
            KeyboardShortcutRules.allowed(KeyboardShortcutAction.PromptAlways, KeyboardShortcutTextFocus.CustomAnswer, false),
        )
        for (action in app) {
            val allowed = KeyboardShortcutRules.allowed(action, KeyboardShortcutTextFocus.CustomAnswer, false)
            assertFalse("$action in the custom answer", allowed)
        }
    }

    @Test
    fun customAnswerFieldIsItsOwnTextFocus() {
        assertEquals(
            KeyboardShortcutTextFocus.CustomAnswer,
            KeyboardShortcutRules.textFocus(OpenCodeFocusedTextInput.QuestionCustomAnswer, nativeTextEditorFocused = false),
        )
        // The field also reports as a plain text input; the custom answer wins.
        val registry = OpenCodeInputFocusRegistry()
        val owner = Any()
        registry.updateTextInput(owner, OpenCodeFocusedTextInput.Field, focused = true)
        registry.updateTextInput(Any(), OpenCodeFocusedTextInput.QuestionCustomAnswer, focused = true)
        assertEquals(OpenCodeFocusedTextInput.QuestionCustomAnswer, registry.focusedTextInput)
    }

    @Test
    fun agreedHelperTitles() {
        assertEquals("New Session", KeyboardShortcutAction.NewSession.title.english())
        assertEquals("Command Palette", KeyboardShortcutAction.CommandPalette.title.english())
        assertEquals("Choose Model", KeyboardShortcutAction.ModelPicker.title.english())
        assertEquals("Settings", KeyboardShortcutAction.Settings.title.english())
        assertEquals("Allow Once", KeyboardShortcutAction.PromptPrimary.title.english())
        assertEquals("Always Allow", KeyboardShortcutAction.PromptAlways.title.english())
        assertEquals("Deny", KeyboardShortcutAction.PromptReject.title.english())
        assertEquals("Show acme-invoices", KeyboardShortcutRules.projectShortcutTitle("acme-invoices").english())
        assertEquals("Project 3", KeyboardShortcutAction.Project3.title.english())
    }

    @Test
    fun exactlyOneNewSessionHandlerActs() {
        // Home alone: Home creates in the current scope.
        assertTrue(KeyboardShortcutRules.homeHandlesNewSession(sessionChatBeside = false))
        // A chat beside Home (including a subagent's) creates in its own project; Home stands down.
        assertFalse(KeyboardShortcutRules.homeHandlesNewSession(sessionChatBeside = true))
    }

    @Test
    fun chatNewSessionWinsOverHomeOnTheHost() {
        val host = KeyboardShortcutHost()
        val calls = mutableListOf<String>()
        val home = host.register(KeyboardShortcutAction.NewSession) { calls += "home" }
        // A chat opens beside Home: Home's handler stands down, the chat's registers.
        host.unregister(home)
        host.register(KeyboardShortcutAction.NewSession) { calls += "chat" }
        host.handleKey(
            isDown = true,
            keyCode = KeyEvent.KEYCODE_N,
            ctrl = true,
            alt = false,
            shift = false,
            meta = false,
            repeatCount = 0,
            nativeTextEditorFocused = false,
        )
        assertEquals(listOf("chat"), calls)
    }

    @Test
    fun projectShortcutPicksTheNthProject() {
        val ids = listOf("a", "b", "c")
        assertEquals("a", KeyboardShortcutRules.projectForShortcut(ids, 1))
        assertEquals("c", KeyboardShortcutRules.projectForShortcut(ids, 3))
        assertNull(KeyboardShortcutRules.projectForShortcut(ids, 4))
        assertNull(KeyboardShortcutRules.projectForShortcut(emptyList(), 1))
    }

    @Test
    fun groupsFollowTheSharedMap() {
        assertEquals(KeyboardShortcutGroupKind.Session, KeyboardShortcutAction.NewSession.group)
        assertEquals(KeyboardShortcutGroupKind.Session, KeyboardShortcutAction.CommandPalette.group)
        assertEquals(KeyboardShortcutGroupKind.Session, KeyboardShortcutAction.ModelPicker.group)
        assertEquals(KeyboardShortcutGroupKind.Prompt, KeyboardShortcutAction.PromptPrimary.group)
        assertEquals(KeyboardShortcutGroupKind.Prompt, KeyboardShortcutAction.PromptAlways.group)
        assertEquals(KeyboardShortcutGroupKind.Prompt, KeyboardShortcutAction.PromptReject.group)
        assertEquals(KeyboardShortcutGroupKind.Go, KeyboardShortcutAction.Settings.group)
        KeyboardShortcutAction.projects.forEach { assertEquals(KeyboardShortcutGroupKind.Go, it.group) }
    }

    @Test
    fun hostRunsPromptChordWithNothingFocusedAndNeverRepeatsIt() {
        val host = KeyboardShortcutHost()
        var allowed = 0
        host.register(KeyboardShortcutAction.PromptPrimary, UiText.verbatim("Allow Once")) { allowed += 1 }

        fun press(repeat: Int) =
            host.handleKey(
                isDown = true,
                keyCode = KeyEvent.KEYCODE_ENTER,
                ctrl = true,
                alt = false,
                shift = false,
                meta = false,
                repeatCount = repeat,
                nativeTextEditorFocused = false,
            )
        assertTrue(press(0))
        assertTrue(press(1))
        assertTrue(press(2))
        assertEquals(1, allowed)
    }

    @Test
    fun hostKeepsCtrlBackspaceForTheCustomAnswerField() {
        val host = KeyboardShortcutHost()
        var dismissed = 0
        var submitted = 0
        host.register(KeyboardShortcutAction.PromptReject, UiText.verbatim("Dismiss")) { dismissed += 1 }
        host.register(KeyboardShortcutAction.PromptPrimary, UiText.verbatim("Submit")) { submitted += 1 }
        host.inputFocus.updateTextInput(Any(), OpenCodeFocusedTextInput.QuestionCustomAnswer, focused = true)

        fun press(keyCode: Int) =
            host.handleKey(
                isDown = true,
                keyCode = keyCode,
                ctrl = true,
                alt = false,
                shift = false,
                meta = false,
                repeatCount = 0,
                nativeTextEditorFocused = false,
            )
        assertFalse(press(KeyEvent.KEYCODE_DEL))
        assertTrue(press(KeyEvent.KEYCODE_ENTER))
        assertEquals(0, dismissed)
        assertEquals(1, submitted)
    }

    @Test
    fun helperTitlesComeFromTheNewestHandler() {
        val host = KeyboardShortcutHost()
        host.register(KeyboardShortcutAction.PromptPrimary, UiText.verbatim("Allow Once")) {}
        host.register(KeyboardShortcutAction.PromptReject, UiText.verbatim("Dismiss")) {}
        host.register(KeyboardShortcutAction.PromptPrimary, UiText.verbatim("Submit")) {}
        host.register(KeyboardShortcutAction.NewSession) {}
        val titles = host.activeShortcutTitles().mapValues { it.value.english() }
        assertEquals("Submit", titles[KeyboardShortcutAction.PromptPrimary])
        assertEquals("Dismiss", titles[KeyboardShortcutAction.PromptReject])
        assertEquals("New Session", titles[KeyboardShortcutAction.NewSession])
        assertEquals(3, titles.size)
    }
}
