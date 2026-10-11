package dev.opencode.mobile.ui.keyboard

import android.view.KeyEvent
import dev.opencode.mobile.ui.components.OpenCodeFocusedTextInput

/** Hardware keyboard shortcuts for the tablet split layout and Session Chat. */
enum class KeyboardShortcutAction(
    val title: String,
    val keyCode: Int,
    val ctrl: Boolean,
    val alt: Boolean = false,
    val shift: Boolean = false,
    /** Whether holding the chord repeats the action. */
    val repeats: Boolean = false,
) {
    /** Ctrl+Alt+S: Home and Settings while they show the split layout. */
    ToggleSidebar(title = "Toggle Sidebar", keyCode = KeyEvent.KEYCODE_S, ctrl = true, alt = true),

    /** Ctrl+Tab: Home split layout. */
    NextSession(title = "Next Session", keyCode = KeyEvent.KEYCODE_TAB, ctrl = true, repeats = true),

    /** Ctrl+Shift+Tab: Home split layout. */
    PreviousSession(
        title = "Previous Session",
        keyCode = KeyEvent.KEYCODE_TAB,
        ctrl = true,
        shift = true,
        repeats = true,
    ),

    /** Ctrl+L: any visible Session Chat with a prompt composer. */
    FocusComposer(title = "Focus Composer", keyCode = KeyEvent.KEYCODE_L, ctrl = true),
    ;

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
            action.keyCode == keyCode &&
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
    ): Boolean = !modalSurfaceShown && focus != KeyboardShortcutTextFocus.OtherTextField

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
