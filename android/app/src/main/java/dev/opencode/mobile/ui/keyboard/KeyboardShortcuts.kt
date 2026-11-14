package dev.opencode.mobile.ui.keyboard

import android.view.KeyEvent
import androidx.annotation.StringRes
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.components.OpenCodeFocusedTextInput

/** Groups of the Keyboard Shortcuts Helper (Meta+/), in the order they are listed. */
enum class KeyboardShortcutGroupKind(
    @param:StringRes private val labelRes: Int,
) {
    Session(R.string.keyboard_shortcuts_group_session),
    Prompt(R.string.keyboard_shortcuts_group_prompt),
    Go(R.string.keyboard_shortcuts_group_go),
    ;

    val label: UiText
        get() = UiText.resource(labelRes)
}

/**
 * Hardware keyboard shortcuts (#358, #869 Tier 2). [title] is the default Keyboard Shortcuts
 * Helper title; a handler may name its chord more precisely (Ctrl+Enter is "Allow Once" on a
 * permission and "Submit" on a question).
 */
enum class KeyboardShortcutAction(
    @param:StringRes private val titleRes: Int,
    val keyCode: Int,
    val ctrl: Boolean,
    val group: KeyboardShortcutGroupKind,
    val alt: Boolean = false,
    val shift: Boolean = false,
    /** Whether holding the chord repeats the action. */
    val repeats: Boolean = false,
    /** Another key that means the same chord (the numeric keypad Enter). */
    val alternateKeyCode: Int? = null,
    /** Ctrl+1…Ctrl+9: the dock position the title names. */
    private val projectNumber: Int? = null,
) {
    /** Ctrl+N: Home and Session Chat, in the current context's project. */
    NewSession(titleRes = R.string.keyboard_shortcuts_new_session, keyCode = KeyEvent.KEYCODE_N, ctrl = true, group = KeyboardShortcutGroupKind.Session),

    /** Ctrl+K: Session Chat, the slash-command list. */
    CommandPalette(titleRes = R.string.keyboard_shortcuts_command_palette, keyCode = KeyEvent.KEYCODE_K, ctrl = true, group = KeyboardShortcutGroupKind.Session),

    /** Ctrl+Shift+M: Session Chat, the model picker. */
    ModelPicker(
        titleRes = R.string.keyboard_shortcuts_choose_model,
        keyCode = KeyEvent.KEYCODE_M,
        ctrl = true,
        shift = true,
        group = KeyboardShortcutGroupKind.Session,
    ),

    /** Ctrl+L: any visible Session Chat with a prompt composer. */
    FocusComposer(titleRes = R.string.keyboard_shortcuts_focus_composer, keyCode = KeyEvent.KEYCODE_L, ctrl = true, group = KeyboardShortcutGroupKind.Session),

    /** Ctrl+Enter: Allow once on a permission; Next or Submit on a question. */
    PromptPrimary(
        titleRes = R.string.keyboard_shortcuts_allow_once,
        keyCode = KeyEvent.KEYCODE_ENTER,
        ctrl = true,
        group = KeyboardShortcutGroupKind.Prompt,
        alternateKeyCode = KeyEvent.KEYCODE_NUMPAD_ENTER,
    ),

    /** Ctrl+Alt+Enter: Always allow on a permission that offers it. */
    PromptAlways(
        titleRes = R.string.keyboard_shortcuts_always_allow,
        keyCode = KeyEvent.KEYCODE_ENTER,
        ctrl = true,
        alt = true,
        group = KeyboardShortcutGroupKind.Prompt,
        alternateKeyCode = KeyEvent.KEYCODE_NUMPAD_ENTER,
    ),

    /** Ctrl+Backspace: Deny a permission; Dismiss a question. */
    PromptReject(titleRes = R.string.keyboard_shortcuts_deny, keyCode = KeyEvent.KEYCODE_DEL, ctrl = true, group = KeyboardShortcutGroupKind.Prompt),

    /** Ctrl+Alt+Backspace: open "Deny with a note" on a permission (#946). */
    PromptRejectWithNote(
        titleRes = R.string.keyboard_shortcuts_deny_with_note,
        keyCode = KeyEvent.KEYCODE_DEL,
        ctrl = true,
        alt = true,
        group = KeyboardShortcutGroupKind.Prompt,
    ),

    /** Ctrl+,: wherever Settings is reachable. */
    Settings(titleRes = R.string.keyboard_shortcuts_settings, keyCode = KeyEvent.KEYCODE_COMMA, ctrl = true, group = KeyboardShortcutGroupKind.Go),

    /** Ctrl+F: Home, Search sessions (#929). */
    SearchSessions(
        titleRes = R.string.keyboard_shortcuts_search_sessions,
        keyCode = KeyEvent.KEYCODE_F,
        ctrl = true,
        group = KeyboardShortcutGroupKind.Go,
    ),

    /** Ctrl+1…Ctrl+9: Home, the Nth project in the dock's project order. */
    Project1(R.string.keyboard_shortcuts_project, KeyEvent.KEYCODE_1, ctrl = true, group = KeyboardShortcutGroupKind.Go, projectNumber = 1),
    Project2(R.string.keyboard_shortcuts_project, KeyEvent.KEYCODE_2, ctrl = true, group = KeyboardShortcutGroupKind.Go, projectNumber = 2),
    Project3(R.string.keyboard_shortcuts_project, KeyEvent.KEYCODE_3, ctrl = true, group = KeyboardShortcutGroupKind.Go, projectNumber = 3),
    Project4(R.string.keyboard_shortcuts_project, KeyEvent.KEYCODE_4, ctrl = true, group = KeyboardShortcutGroupKind.Go, projectNumber = 4),
    Project5(R.string.keyboard_shortcuts_project, KeyEvent.KEYCODE_5, ctrl = true, group = KeyboardShortcutGroupKind.Go, projectNumber = 5),
    Project6(R.string.keyboard_shortcuts_project, KeyEvent.KEYCODE_6, ctrl = true, group = KeyboardShortcutGroupKind.Go, projectNumber = 6),
    Project7(R.string.keyboard_shortcuts_project, KeyEvent.KEYCODE_7, ctrl = true, group = KeyboardShortcutGroupKind.Go, projectNumber = 7),
    Project8(R.string.keyboard_shortcuts_project, KeyEvent.KEYCODE_8, ctrl = true, group = KeyboardShortcutGroupKind.Go, projectNumber = 8),
    Project9(R.string.keyboard_shortcuts_project, KeyEvent.KEYCODE_9, ctrl = true, group = KeyboardShortcutGroupKind.Go, projectNumber = 9),

    /** Ctrl+Tab: Home split layout. */
    NextSession(
        titleRes = R.string.keyboard_shortcuts_next_session,
        keyCode = KeyEvent.KEYCODE_TAB,
        ctrl = true,
        group = KeyboardShortcutGroupKind.Go,
        repeats = true,
    ),

    /** Ctrl+Shift+Tab: Home split layout. */
    PreviousSession(
        titleRes = R.string.keyboard_shortcuts_previous_session,
        keyCode = KeyEvent.KEYCODE_TAB,
        ctrl = true,
        shift = true,
        group = KeyboardShortcutGroupKind.Go,
        repeats = true,
    ),

    /** Ctrl+Alt+S: Home and Settings while they show the split layout. */
    ToggleSidebar(
        titleRes = R.string.keyboard_shortcuts_toggle_sidebar,
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

    /** Default Keyboard Shortcuts Helper title; a handler may name its chord more precisely. */
    val title: UiText
        get() = projectNumber?.let { UiText.resource(titleRes, it) } ?: UiText.resource(titleRes)

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

    /** The "Type your own answer" field of a question prompt, or a permission's deny note. */
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
            OpenCodeFocusedTextInput.QuestionCustomAnswer,
            OpenCodeFocusedTextInput.PermissionNote,
            -> KeyboardShortcutTextFocus.CustomAnswer
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
    fun projectShortcutTitle(projectTitle: String): UiText = UiText.resource(R.string.keyboard_shortcuts_show_project, projectTitle)

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
