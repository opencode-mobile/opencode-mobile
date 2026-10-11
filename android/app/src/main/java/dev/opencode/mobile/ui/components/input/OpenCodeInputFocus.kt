package dev.opencode.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

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
