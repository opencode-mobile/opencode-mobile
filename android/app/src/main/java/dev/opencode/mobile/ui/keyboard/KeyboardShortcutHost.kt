package dev.opencode.mobile.ui.keyboard

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.AbstractComposeView
import dev.opencode.mobile.ui.components.OpenCodeInputFocusRegistry

/**
 * Window-level dispatcher for [KeyboardShortcutAction]s. Screens register a handler while the
 * shortcut applies (see [KeyboardShortcutHandler]); the Activity forwards key events before the
 * view hierarchy sees them, so Compose focus traversal never swallows Ctrl+Tab.
 */
class KeyboardShortcutHost(
    val inputFocus: OpenCodeInputFocusRegistry = OpenCodeInputFocusRegistry(),
) {
    private class Registration(
        val action: KeyboardShortcutAction,
        val handler: () -> Unit,
    )

    private val registrations = mutableListOf<Registration>()
    private var consumedDownKeyCode: Int? = null

    fun register(
        action: KeyboardShortcutAction,
        handler: () -> Unit,
    ): Any = Registration(action, handler).also(registrations::add)

    fun unregister(registration: Any) {
        registrations.remove(registration)
    }

    /** Shortcuts that currently apply, in declaration order. */
    fun activeActions(): List<KeyboardShortcutAction> =
        KeyboardShortcutAction.entries.filter { action -> registrations.any { it.action == action } }

    /**
     * Runs the newest handler for a matching chord and returns true when the event is consumed.
     * The key-up of a consumed key-down is consumed too.
     */
    fun handleKey(
        isDown: Boolean,
        keyCode: Int,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        meta: Boolean,
        repeatCount: Int,
        nativeTextEditorFocused: Boolean,
    ): Boolean {
        if (!isDown) {
            if (consumedDownKeyCode == keyCode) {
                consumedDownKeyCode = null
                return true
            }
            return false
        }
        val action = KeyboardShortcutRules.match(keyCode, ctrl, alt, shift, meta) ?: return false
        val focus = KeyboardShortcutRules.textFocus(inputFocus.focusedTextInput, nativeTextEditorFocused)
        if (!KeyboardShortcutRules.allowed(focus, inputFocus.hasModalSurface)) return false
        val registration = registrations.lastOrNull { it.action == action } ?: return false
        consumedDownKeyCode = keyCode
        if (repeatCount == 0 || action.repeats) {
            registration.handler()
        }
        return true
    }

    fun handleKeyEvent(
        event: KeyEvent,
        focusedView: View?,
    ): Boolean {
        val isDown =
            when (event.action) {
                KeyEvent.ACTION_DOWN -> true
                KeyEvent.ACTION_UP -> false
                else -> return false
            }
        return handleKey(
            isDown = isDown,
            keyCode = event.keyCode,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
            meta = event.isMetaPressed,
            repeatCount = event.repeatCount,
            nativeTextEditorFocused = focusedView.isNativeTextEditor(),
        )
    }

    /** Group for the system Keyboard Shortcuts Helper (Meta+/), or null when nothing applies. */
    fun shortcutGroup(): KeyboardShortcutGroup? {
        val actions = activeActions()
        if (actions.isEmpty()) return null
        return KeyboardShortcutGroup(
            GROUP_LABEL,
            actions.map { action -> KeyboardShortcutInfo(action.title, action.keyCode, action.metaState) },
        )
    }

    private companion object {
        const val GROUP_LABEL = "Sessions"
    }
}

/**
 * A focused platform text editor outside Compose. Compose text fields share one host view whose
 * parent is the [AbstractComposeView]; those report focus through [OpenCodeInputFocusRegistry].
 */
private fun View?.isNativeTextEditor(): Boolean {
    if (this == null) return false
    if (parent is AbstractComposeView) return false
    return onCheckIsTextEditor()
}

/** Null outside the Activity (previews, tests): handlers then register nowhere. */
val LocalKeyboardShortcutHost = staticCompositionLocalOf<KeyboardShortcutHost?> { null }

/**
 * Registers [onShortcut] for [action] while this call is composed and [enabled]. The newest
 * registration for an action wins, so a screen pushed on top takes over.
 */
@Composable
fun KeyboardShortcutHandler(
    action: KeyboardShortcutAction,
    enabled: Boolean = true,
    onShortcut: () -> Unit,
) {
    val host = LocalKeyboardShortcutHost.current
    val currentOnShortcut by rememberUpdatedState(onShortcut)
    DisposableEffect(host, action, enabled) {
        val registration =
            if (host != null && enabled) {
                host.register(action) { currentOnShortcut() }
            } else {
                null
            }
        onDispose { registration?.let { host?.unregister(it) } }
    }
}
