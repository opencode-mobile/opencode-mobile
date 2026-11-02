package dev.opencode.mobile.ui.keyboard

import android.view.KeyEvent
import dev.opencode.mobile.ui.components.OpenCodeFocusedTextInput

/** Groups of the Keyboard Shortcuts Helper (Meta+/), in the order they are listed. */
enum class KeyboardShortcutGroupKind(
    val label: String,
) {
    Session("Session"),
    Prompt("Prompt"),
    Go("Go"),
}

/**
 * Hardware keyboard shortcuts (#358, #869 Tier 2). [title] is the default Keyboard Shortcuts
 * Helper title; a handler may name its chord more precisely (Ctrl+Enter is "Allow Once" on a
 * permission and "Submit" on a question).
 */
enum class KeyboardShortcutAction(
    val title: String,
    val keyCode: Int,
    val ctrl: Boolean,
    val group: KeyboardShortcutGroupKind,
    val alt: Boolean = false,
    val shift: Boolean = false,
    /** Whether holding the chord repeats the action. */
    val repeats: Boolean = false,
    /** Another key that means the same chord (the numeric keypad Enter). */
    val alternateKeyCode: Int? = null,
) {
    /** Ctrl+N: Home and Session Chat, in the current context's project. */
    NewSession(title = "New Session", keyCode = KeyEvent.KEYCODE_N, ctrl = true, group = KeyboardShortcutGroupKind.Session),

    /** Ctrl+K: Session Chat, the slash-command list. */
    CommandPalette(title = "Command Palette", keyCode = KeyEvent.KEYCODE_K, ctrl = true, group = KeyboardShortcutGroupKind.Session),

    /** Ctrl+Shift+M: Session Chat, the model picker. */
    ModelPicker(
        title = "Choose Model",
        keyCode = KeyEvent.KEYCODE_M,
        ctrl = true,
        shift = true,
        group = KeyboardShortcutGroupKind.Session,
    ),

    /** Ctrl+L: any visible Session Chat with a prompt composer. */
    FocusComposer(title = "Focus Composer", keyCode = KeyEvent.KEYCODE_L, ctrl = true, group = KeyboardShortcutGroupKind.Session),

    /** Ctrl+Enter: Allow once on a permission; Next or Submit on a question. */
    PromptPrimary(
        title = "Allow Once",
        keyCode = KeyEvent.KEYCODE_ENTER,
        ctrl = true,
        group = KeyboardShortcutGroupKind.Prompt,
        alternateKeyCode = KeyEvent.KEYCODE_NUMPAD_ENTER,
    ),

    /** Ctrl+Alt+Enter: Always allow on a permission that offers it. */
    PromptAlways(
        title = "Always Allow",
        keyCode = KeyEvent.KEYCODE_ENTER,
        ctrl = true,
        alt = true,
        group = KeyboardShortcutGroupKind.Prompt,
        alternateKeyCode = KeyEvent.KEYCODE_NUMPAD_ENTER,
    ),

    /** Ctrl+Backspace: Deny a permission; Dismiss a question. */
    PromptReject(title = "Deny", keyCode = KeyEvent.KEYCODE_DEL, ctrl = true, group = KeyboardShortcutGroupKind.Prompt),

    /** Ctrl+,: wherever Settings is reachable. */
    Settings(title = "Settings", keyCode = KeyEvent.KEYCODE_COMMA, ctrl = true, group = KeyboardShortcutGroupKind.Go),

    /** Ctrl+1…Ctrl+9: Home, the Nth project in the dock's project order. */
    Project1(title = "Project 1", keyCode = KeyEvent.KEYCODE_1, ctrl = true, group = KeyboardShortcutGroupKind.Go),
    Project2(title = "Project 2", keyCode = KeyEvent.KEYCODE_2, ctrl = true, group = KeyboardShortcutGroupKind.Go),
    Project3(title = "Project 3", keyCode = KeyEvent.KEYCODE_3, ctrl = true, group = KeyboardShortcutGroupKind.Go),
    Project4(title = "Project 4", keyCode = KeyEvent.KEYCODE_4, ctrl = true, group = KeyboardShortcutGroupKind.Go),
    Project5(title = "Project 5", keyCode = KeyEvent.KEYCODE_5, ctrl = true, group = KeyboardShortcutGroupKind.Go),
    Project6(title = "Project 6", keyCode = KeyEvent.KEYCODE_6, ctrl = true, group = KeyboardShortcutGroupKind.Go),
    Project7(title = "Project 7", keyCode = KeyEvent.KEYCODE_7, ctrl = true, group = KeyboardShortcutGroupKind.Go),
    Project8(title = "Project 8", keyCode = KeyEvent.KEYCODE_8, ctrl = true, group = KeyboardShortcutGroupKind.Go),
    Project9(title = "Project 9", keyCode = KeyEvent.KEYCODE_9, ctrl = true, group = KeyboardShortcutGroupKind.Go),

    /** Ctrl+Tab: Home split layout. */
    NextSession(
        title = "Next Session",
        keyCode = KeyEvent.KEYCODE_TAB,
        ctrl = true,
        group = KeyboardShortcutGroupKind.Go,
        repeats = true,
    ),

    /** Ctrl+Shift+Tab: Home split layout. */
    PreviousSession(
        title = "Previous Session",
        keyCode = KeyEvent.KEYCODE_TAB,
        ctrl = true,
        shift = true,
        group = KeyboardShortcutGroupKind.Go,
        repeats = true,
    ),

    /** Ctrl+Alt+S: Home and Settings while they show the split layout. */
    ToggleSidebar(
        title = "Toggle Sidebar",
        keyCode = KeyEvent.KEYCODE_S,
        ctrl = true,
        alt = true,
        group = KeyboardShortcutGroupKind.Go,
    ),
    ;

    companion object {
        /** Ctrl+1…Ctrl+9 in project order; index 0 is Project 1. */
        val projects: List<KeyboardShortcutAction> =
            listOf(Project1, Project2, Project3, Project4, Project5, Project6, Project7, Project8, Project9)
    }

    /** `KeyEvent.META_*` mask for `KeyboardShortcutInfo`. */
    val metaState: Int
        get() =
            KeyEvent.META_CTRL_ON or
                (if (alt) KeyEvent.META_ALT_ON else 0) or
                (if (shift) KeyEvent.META_SHIFT_ON else 0)
}

/** Text focus as far as shortcut gating cares. */
enum class KeyboardShortcutTextFocus {
    None,
    Composer,

    /** The "Type your own answer" field of a question prompt. */
    CustomAnswer,
    OtherTextField,
}

enum class SessionStepDirection {
    Next,
    Previous,
}

/** Pure shortcut rules: chord matching, focus gating, and session stepping. */
object KeyboardShortcutRules {
    /** Exact-modifier match; Meta (Search/Windows/Command) never matches so system chords pass through. */
    fun match(
        keyCode: Int,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        meta: Boolean,
    ): KeyboardShortcutAction? {
        if (meta) return null
        return KeyboardShortcutAction.entries.firstOrNull { action ->
            (action.keyCode == keyCode || action.alternateKeyCode == keyCode) &&
                action.ctrl == ctrl &&
                action.alt == alt &&
                action.shift == shift
        }
    }

    /**
     * Design-system inputs report their own focus; [nativeTextEditorFocused] covers focused
     * platform views outside Compose (for example the terminal emulator).
     */
    fun textFocus(
        focusedInput: OpenCodeFocusedTextInput?,
        nativeTextEditorFocused: Boolean,
    ): KeyboardShortcutTextFocus =
        when (focusedInput) {
            OpenCodeFocusedTextInput.Field -> KeyboardShortcutTextFocus.OtherTextField
            OpenCodeFocusedTextInput.SessionComposer -> KeyboardShortcutTextFocus.Composer
            OpenCodeFocusedTextInput.QuestionCustomAnswer -> KeyboardShortcutTextFocus.CustomAnswer
            null ->
                if (nativeTextEditorFocused) {
                    KeyboardShortcutTextFocus.OtherTextField
                } else {
                    KeyboardShortcutTextFocus.None
                }
        }

    /** Shortcuts stay out of the way of text fields other than the composer, and of modal surfaces. */
    fun allowed(
        focus: KeyboardShortcutTextFocus,
        modalSurfaceShown: Boolean,
    ): Boolean =
        !modalSurfaceShown &&
            focus != KeyboardShortcutTextFocus.OtherTextField &&
            focus != KeyboardShortcutTextFocus.CustomAnswer

    /**
     * Per-chord gating. Nothing fires over a sheet, dialog or menu, or while a text field other
     * than the composer has focus. Prompt chords fire while their prompt shows even with nothing
     * focused. In "Type your own answer", only Ctrl+Enter (save and Next / Submit) fires; Ctrl+
     * Backspace there deletes text.
     */
    fun allowed(
        action: KeyboardShortcutAction,
        focus: KeyboardShortcutTextFocus,
        modalSurfaceShown: Boolean,
    ): Boolean {
        if (modalSurfaceShown) return false
        return when (focus) {
            KeyboardShortcutTextFocus.OtherTextField -> false
            KeyboardShortcutTextFocus.CustomAnswer -> action == KeyboardShortcutAction.PromptPrimary
            KeyboardShortcutTextFocus.None, KeyboardShortcutTextFocus.Composer -> true
        }
    }

    /**
     * Whether Home's Ctrl+N applies. While a Session Chat shows beside Home (the split pane has a
     * selected session), the chat owns Ctrl+N and creates the session in its own project, so
     * exactly one handler acts.
     */
    fun homeHandlesNewSession(sessionChatBeside: Boolean): Boolean = !sessionChatBeside

    /** Helper title of Ctrl+[number] for the project [projectTitle]. */
    fun projectShortcutTitle(projectTitle: String): String = "Show $projectTitle"

    /** The filter id Ctrl+[number] selects: the Nth project in dock order, or null past the end. */
    fun projectForShortcut(
        projectIds: List<String>,
        number: Int,
    ): String? = projectIds.getOrNull(number - 1)

    /**
     * The session to select when stepping through [orderedIds] (visible list order, top to
     * bottom). No wrap-around. With no selection, or a selection that is not in the list,
     * Next picks the first session and Previous the last.
     */
    fun adjacentSessionId(
        orderedIds: List<String>,
        selectedId: String?,
        direction: SessionStepDirection,
    ): String? {
        if (orderedIds.isEmpty()) return null
        val index = selectedId?.let(orderedIds::indexOf) ?: -1
        if (index < 0) {
            return when (direction) {
                SessionStepDirection.Next -> orderedIds.first()
                SessionStepDirection.Previous -> orderedIds.last()
            }
        }
        val target =
            when (direction) {
                SessionStepDirection.Next -> index + 1
                SessionStepDirection.Previous -> index - 1
            }
        return orderedIds.getOrNull(target)
    }
}
