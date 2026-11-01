package dev.opencode.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import dev.opencode.mobile.ui.keyboard.KeyboardNavigationRules
import dev.opencode.mobile.ui.keyboard.TextFieldTabAction

/** Which kind of design-system text input holds focus. */
enum class OpenCodeFocusedTextInput {
    /** The prompt text input of [OpenCodeSessionComposer]. */
    SessionComposer,

    /** Any other text input: [OpenCodeTextInput], [OpenCodeServerAddressInput], and so on. */
    Field,
}

/**
 * Window-wide view of design-system input focus: which text input is focused and whether a
 * modal surface (bottom sheet, dialog) covers the screen. Text inputs and modal surfaces report
 * into the registry provided by [LocalOpenCodeInputFocusRegistry]; app-level keyboard handling
 * reads it. Main-thread only.
 */
class OpenCodeInputFocusRegistry {
    private val focusedInputs = LinkedHashMap<Any, OpenCodeFocusedTextInput>()
    private val modalSurfaces = LinkedHashSet<Any>()
    private val promptEntries = LinkedHashMap<Any, () -> Boolean>()

    /**
     * Moves keyboard focus into the newest waiting permission or question prompt and returns
     * whether it did; null when no prompt waits. See [OpenCodePromptKeyboardEntryEffect].
     */
    val promptEntry: (() -> Boolean)?
        get() = promptEntries.values.lastOrNull()

    fun updatePromptEntry(
        owner: Any,
        entry: (() -> Boolean)?,
    ) {
        if (entry != null) {
            promptEntries[owner] = entry
        } else {
            promptEntries.remove(owner)
        }
    }

    /** [OpenCodeFocusedTextInput.Field] wins if both kinds report focus during a hand-off. */
    val focusedTextInput: OpenCodeFocusedTextInput?
        get() =
            when {
                OpenCodeFocusedTextInput.Field in focusedInputs.values -> OpenCodeFocusedTextInput.Field
                OpenCodeFocusedTextInput.SessionComposer in focusedInputs.values ->
                    OpenCodeFocusedTextInput.SessionComposer
                else -> null
            }

    val hasModalSurface: Boolean
        get() = modalSurfaces.isNotEmpty()

    /**
     * The design-system control that holds keyboard focus right now (see `openCodeFocusMemory`),
     * or null. Clickable controls take focus only in keyboard input mode, so a value means the
     * user is driving with the keyboard.
     */
    var focusedControl: FocusRequester? = null
        private set

    fun updateFocusedControl(
        control: FocusRequester,
        focused: Boolean,
    ) {
        if (focused) {
            focusedControl = control
        } else if (focusedControl === control) {
            focusedControl = null
        }
    }

    /**
     * Lets go of keyboard focus without handing it to another control (see `KeyboardFocusRoot`).
     * Null outside the app root.
     */
    var parkFocus: (() -> Unit)? = null

    fun updateTextInput(
        owner: Any,
        input: OpenCodeFocusedTextInput,
        focused: Boolean,
    ) {
        if (focused) {
            focusedInputs[owner] = input
        } else {
            focusedInputs.remove(owner)
        }
    }

    fun removeTextInput(owner: Any) {
        focusedInputs.remove(owner)
    }

    fun updateModalSurface(
        owner: Any,
        shown: Boolean,
    ) {
        if (shown) {
            modalSurfaces.add(owner)
        } else {
            modalSurfaces.remove(owner)
        }
    }
}

/** Null outside the app root (previews, tests): inputs then report nowhere. */
val LocalOpenCodeInputFocusRegistry = staticCompositionLocalOf<OpenCodeInputFocusRegistry?> { null }

/**
 * Focus callback for one text input. Wire it to `Modifier.onFocusChanged { report(it.isFocused) }`;
 * the report is withdrawn when the input leaves composition, even if it never reported blur.
 */
@Composable
internal fun rememberOpenCodeTextInputFocusReport(input: OpenCodeFocusedTextInput): (Boolean) -> Unit {
    val registry = LocalOpenCodeInputFocusRegistry.current
    val owner = remember { Any() }
    DisposableEffect(registry, owner) {
        onDispose { registry?.removeTextInput(owner) }
    }
    return remember(registry, owner, input) {
        { focused -> registry?.updateTextInput(owner, input, focused) }
    }
}

/** Marks a modal surface as covering the window while [shown]. */
@Composable
internal fun OpenCodeModalSurfaceEffect(shown: Boolean) {
    val registry = LocalOpenCodeInputFocusRegistry.current
    val owner = remember { Any() }
    DisposableEffect(registry, owner, shown) {
        registry?.updateModalSurface(owner, shown)
        onDispose { registry?.updateModalSurface(owner, false) }
    }
}

/**
 * Registers a waiting prompt so the first Tab or arrow, while nothing holds focus, moves into it
 * through [enter]. [enter] returns whether focus moved.
 */
@Composable
internal fun OpenCodePromptKeyboardEntryEffect(enter: () -> Boolean) {
    val registry = LocalOpenCodeInputFocusRegistry.current
    val owner = remember { Any() }
    val currentEnter by rememberUpdatedState(enter)
    DisposableEffect(registry, owner) {
        registry?.updatePromptEntry(owner) { currentEnter() }
        onDispose { registry?.updatePromptEntry(owner, null) }
    }
}

/** True while a hardware keyboard is attached and not hidden. Recomposes when one connects. */
@Composable
fun rememberOpenCodeHardwareKeyboardConnected(): Boolean {
    val configuration = LocalConfiguration.current
    return KeyboardNavigationRules.hardwareKeyboardConnected(configuration.keyboard, configuration.hardKeyboardHidden)
}

/**
 * Hardware Tab and Shift+Tab move focus out of a multi-line text field instead of typing a tab.
 * Handled before the IME, which would otherwise take the key. Returns whether it handled [event].
 */
internal fun FocusManager.moveFocusOnHardwareTab(
    event: KeyEvent,
    hardwareKeyboard: Boolean,
): Boolean {
    val action =
        KeyboardNavigationRules.textFieldTab(
            isTab = event.key == Key.Tab,
            fromHardwareKeyboard = KeyboardNavigationRules.fromHardwareKeyboard(hardwareKeyboard, event.nativeKeyEvent.flags),
            shift = event.isShiftPressed,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            meta = event.isMetaPressed,
        )
    if (action == TextFieldTabAction.PassThrough) return false
    if (event.type == KeyEventType.KeyDown) {
        moveFocus(if (action == TextFieldTabAction.Next) FocusDirection.Next else FocusDirection.Previous)
    }
    return true
}

/**
 * Leaves the focused text input, as Done does. With a hardware keyboard focus is parked on the
 * window root (see `KeyboardFocusRoot`); a plain clear would let Android hand focus to the first
 * control on screen.
 */
@Composable
internal fun rememberOpenCodeLeaveTextInput(): () -> Unit {
    val registry = LocalOpenCodeInputFocusRegistry.current
    val focusManager = LocalFocusManager.current
    val hardwareKeyboard = rememberOpenCodeHardwareKeyboardConnected()
    return remember(registry, focusManager, hardwareKeyboard) {
        {
            val park = registry?.parkFocus
            if (hardwareKeyboard && park != null) park() else focusManager.clearFocus()
        }
    }
}
