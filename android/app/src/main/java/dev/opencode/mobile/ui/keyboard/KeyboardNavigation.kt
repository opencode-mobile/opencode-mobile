package dev.opencode.mobile.ui.keyboard

import android.content.res.Configuration
import android.view.KeyEvent

/** What one Esc press does. Exactly one thing happens per press; see [KeyboardNavigationRules.escape]. */
enum class KeyboardEscapeAction {
    /** Close the topmost sheet, dialog, menu, or popover. */
    CloseSurface,

    /** Leave the focused text field or clear the focused control. */
    LeaveFocus,

    /** First Esc on a busy Session Chat: arm the interrupt and show "Esc again to interrupt". */
    ArmInterrupt,

    /** Second Esc within [KeyboardNavigationRules.INTERRUPT_ARM_WINDOW_MS]: interrupt the running turn. */
    Interrupt,

    /** No earlier step applies: the key goes on to the system, so Esc backs out like Back. */
    PassThrough,
}

/** An Esc decision and the interrupt arm time to keep afterwards (null: disarmed). */
data class KeyboardEscapeResult(
    val action: KeyboardEscapeAction,
    val interruptArmedAtMs: Long?,
)

/** What a hardware Return in the Session Chat composer does. */
enum class ComposerReturnAction {
    /** Send the draft, like the Send button. */
    Send,

    /** Swallow the key: Send is disabled, and a plain Return must not add a new line. */
    Ignore,

    /** Leave the key to the text field (Shift+Return new line, soft keyboard, IME composition). */
    PassThrough,
}

/** What a key does in a question's "Type your own answer" field. */
enum class CustomAnswerKeyAction {
    /** Hardware Return: keep the answer and move focus to Next / Submit. */
    Save,

    /** Esc: leave the field for its option row; nothing is answered. */
    Leave,

    /** Everything else, including Shift+Return for a new line. */
    PassThrough,
}

/** Where a hardware Tab in a multi-line text field moves focus. */
enum class TextFieldTabAction {
    Next,
    Previous,

    /** Not a plain Tab or Shift+Tab: leave the key alone (Ctrl+Tab is Next Session). */
    PassThrough,
}

enum class KeyboardPromptKind {
    Permission,
    Question,
}

enum class PromptRecallDirection {
    /** ↑: an older prompt. */
    Older,

    /** ↓: a newer prompt, and finally back to an empty composer. */
    Newer,
}

/** The composer after a recall step: the history [index] browsed (null: browsing ended) and its [text]. */
data class PromptRecallStep(
    val index: Int?,
    val text: String,
)

/** Pure rules for standard keyboard navigation (#869 Tier 1). */
object KeyboardNavigationRules {
    /** The TUI's double-Esc window: a second Esc less than this long after the first interrupts. */
    const val INTERRUPT_ARM_WINDOW_MS = 5_000L

    /** Hint shown while a prompt waits and the keyboard has not moved into it. */
    const val PROMPT_KEYBOARD_HINT = "Press Tab to answer with the keyboard"

    /** Hint shown while the interrupt is armed. */
    const val INTERRUPT_ARMED_HINT = "Esc again to interrupt"

    /**
     * One Esc press, first match wins: close a surface, leave focus, then (only when
     * [canInterrupt], a busy Session Chat) arm the interrupt or, when armed less than
     * [INTERRUPT_ARM_WINDOW_MS] ago, interrupt. Otherwise the key passes through to the system
     * (Esc = Back on Android). Anything other than arming or interrupting disarms.
     */
    fun escape(
        surfaceOpen: Boolean,
        focusHeld: Boolean,
        canInterrupt: Boolean,
        interruptArmedAtMs: Long?,
        nowMs: Long,
    ): KeyboardEscapeResult =
        when {
            surfaceOpen -> KeyboardEscapeResult(KeyboardEscapeAction.CloseSurface, null)
            focusHeld -> KeyboardEscapeResult(KeyboardEscapeAction.LeaveFocus, null)
            !canInterrupt -> KeyboardEscapeResult(KeyboardEscapeAction.PassThrough, null)
            interruptArmed(canInterrupt, interruptArmedAtMs, nowMs) ->
                KeyboardEscapeResult(KeyboardEscapeAction.Interrupt, null)
            else -> KeyboardEscapeResult(KeyboardEscapeAction.ArmInterrupt, nowMs)
        }

    /** True while an arm from [interruptArmedAtMs] still applies: the session is busy and the window is open. */
    fun interruptArmed(
        canInterrupt: Boolean,
        interruptArmedAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (!canInterrupt || interruptArmedAtMs == null) return false
        val elapsed = nowMs - interruptArmedAtMs
        return elapsed in 0 until INTERRUPT_ARM_WINDOW_MS
    }

    /**
     * Hardware Return in the composer sends when Send is enabled and is swallowed when it is not.
     * Shift+Return, other modifiers, soft-keyboard keys and IME composition keep the field's
     * own behaviour.
     */
    fun composerReturn(
        isReturn: Boolean,
        fromHardwareKeyboard: Boolean,
        shift: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        meta: Boolean,
        composing: Boolean,
        sendEnabled: Boolean,
        suggestionsOpen: Boolean = false,
    ): ComposerReturnAction =
        when {
            !isReturn || !fromHardwareKeyboard || composing -> ComposerReturnAction.PassThrough
            shift || ctrl || alt || meta -> ComposerReturnAction.PassThrough
            // An open `@` or `/` suggestion list owns Return; it never sends the half-typed draft.
            suggestionsOpen -> ComposerReturnAction.Ignore
            sendEnabled -> ComposerReturnAction.Send
            else -> ComposerReturnAction.Ignore
        }

    /**
     * Whether a hardware Return may send, judged from the text the field shows ([liveText]).
     * Return is read before the IME, so the screen state can still hold an older draft
     * ([stateText]); the composer syncs [liveText] first and the screen re-checks before sending.
     */
    fun liveSendEnabled(
        stateSendEnabled: Boolean,
        stateText: String,
        liveText: String,
    ): Boolean =
        when {
            liveText == stateText -> stateSendEnabled
            liveText.isBlank() -> false
            else -> true
        }

    /**
     * Whether focus must be parked when the composer content changes to the prompt [currentPromptId]
     * (a permission or question request id; null for the prompt input). A new or different
     * request starts with nothing focused wherever focus was (composer, timeline, session list),
     * so keys meant for something else cannot answer it and the first Tab enters it. Focus stays
     * while the user types in another text field or a sheet or dialog is open.
     */
    fun parksFocusOnPromptChange(
        previousPromptId: String?,
        currentPromptId: String?,
        otherFieldFocused: Boolean,
        surfaceOpen: Boolean,
    ): Boolean =
        !otherFieldFocused && !surfaceOpen && currentPromptId != null && currentPromptId != previousPromptId

    /** Keys in the custom answer field of a question prompt; see [CustomAnswerKeyAction]. */
    fun customAnswerKey(
        isReturn: Boolean,
        isEscape: Boolean,
        fromHardwareKeyboard: Boolean,
        shift: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        meta: Boolean,
    ): CustomAnswerKeyAction {
        if (ctrl || alt || meta) return CustomAnswerKeyAction.PassThrough
        return when {
            isEscape && !shift -> CustomAnswerKeyAction.Leave
            isReturn && fromHardwareKeyboard && !shift -> CustomAnswerKeyAction.Save
            else -> CustomAnswerKeyAction.PassThrough
        }
    }

    /**
     * A hardware Tab leaves a multi-line text field (the composer, a custom answer) instead of
     * typing a tab character, so keyboard users are never stuck in it. Shift+Tab goes back.
     */
    fun textFieldTab(
        isTab: Boolean,
        fromHardwareKeyboard: Boolean,
        shift: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        meta: Boolean,
    ): TextFieldTabAction =
        when {
            !isTab || !fromHardwareKeyboard || ctrl || alt || meta -> TextFieldTabAction.PassThrough
            shift -> TextFieldTabAction.Previous
            else -> TextFieldTabAction.Next
        }

    /** A key event that came from a physical keyboard rather than a soft keyboard (IME). */
    fun fromHardwareKeyboard(
        hardwareKeyboardConnected: Boolean,
        eventFlags: Int,
    ): Boolean = hardwareKeyboardConnected && (eventFlags and KeyEvent.FLAG_SOFT_KEYBOARD) == 0

    /**
     * The option a bare digit picks in a question prompt (0-based), or null. Digits 1–9 (top
     * row or keypad) pick options 1–9, only once focus is inside the prompt, never while a text
     * field has focus, and never with a modifier held.
     */
    fun questionDigitPick(
        keyCode: Int,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        meta: Boolean,
        focusInsidePrompt: Boolean,
        textFieldFocused: Boolean,
        optionCount: Int,
    ): Int? {
        if (!focusInsidePrompt || textFieldFocused || ctrl || alt || shift || meta) return null
        val index =
            when (keyCode) {
                in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_1
                in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_1
                else -> return null
            }
        return index.takeIf { it < optionCount }
    }

    /**
     * ↑ / ↓ prompt history in the composer, like the TUI. [history] is this session's sent
     * prompts, oldest first. ↑ in an empty composer (no text, no attachments) with the caret at
     * the start shows the newest; further ↑ steps back (staying on the oldest); ↓ steps forward
     * and past the newest returns to an empty composer. While browsing a multi-line prompt, ↑
     * steps only with the caret on its first line and ↓ only on its last line; elsewhere the
     * arrows move the caret. [browsingIndex] is the entry shown now; [unedited] is false once
     * the text differs from it, which ends browsing. Null: the key is not a recall step.
     */
    fun promptRecall(
        history: List<String>,
        browsingIndex: Int?,
        unedited: Boolean,
        text: String,
        hasAttachments: Boolean,
        caretAtStart: Boolean,
        caretOnFirstLine: Boolean,
        caretOnLastLine: Boolean,
        direction: PromptRecallDirection,
    ): PromptRecallStep? {
        val browsing = browsingIndex?.takeIf { unedited && it in history.indices }
        return when (direction) {
            PromptRecallDirection.Older ->
                when {
                    browsing != null ->
                        if (caretOnFirstLine) {
                            (browsing - 1).coerceAtLeast(0).let { PromptRecallStep(it, history[it]) }
                        } else {
                            null
                        }
                    text.isEmpty() && !hasAttachments && caretAtStart && history.isNotEmpty() ->
                        PromptRecallStep(history.lastIndex, history.last())
                    else -> null
                }
            PromptRecallDirection.Newer ->
                when {
                    browsing == null || !caretOnLastLine -> null
                    browsing >= history.lastIndex -> PromptRecallStep(null, "")
                    else -> PromptRecallStep(browsing + 1, history[browsing + 1])
                }
        }
    }

    /** "Press Tab to answer with the keyboard" shows only with a hardware keyboard and focus outside the prompt. */
    fun promptHintVisible(
        hardwareKeyboardConnected: Boolean,
        focusInsidePrompt: Boolean,
    ): Boolean = hardwareKeyboardConnected && !focusInsidePrompt

    /**
     * Where the first Tab or arrow lands in a prompt: the index of the first enabled control in
     * reading order ([actionsEnabled]: a permission's buttons Deny, Always, Allow once; a
     * question's options), or null when none is enabled and the keyboard does not enter.
     * Mirrors iOS `OpenCodePromptFocus.initialTarget(actionsEnabled:)`.
     */
    fun promptInitialTarget(actionsEnabled: List<Boolean>): Int? = actionsEnabled.indexOfFirst { it }.takeIf { it >= 0 }

    /**
     * Whether a key moves focus into a waiting prompt: Tab, Shift+Tab or an arrow while a prompt
     * shows, nothing holds focus, and no sheet or dialog covers the screen. Other keys (Return,
     * Space, letters) never reach the prompt this way, so stray typing cannot answer it.
     */
    fun entersPrompt(
        keyCode: Int,
        ctrl: Boolean,
        alt: Boolean,
        meta: Boolean,
        promptShown: Boolean,
        focusHeld: Boolean,
        surfaceOpen: Boolean,
    ): Boolean {
        if (!promptShown || focusHeld || surfaceOpen || ctrl || alt || meta) return false
        return keyCode in PromptEntryKeys
    }

    /** A physical keyboard is attached and not hidden (`Configuration.keyboard` / `hardKeyboardHidden`). */
    fun hardwareKeyboardConnected(
        keyboard: Int,
        hardKeyboardHidden: Int,
    ): Boolean =
        keyboard != Configuration.KEYBOARD_NOKEYS &&
            keyboard != Configuration.KEYBOARD_UNDEFINED &&
            hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES

    private val PromptEntryKeys =
        setOf(
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
}
