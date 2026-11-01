package dev.opencode.mobile.ui.keyboard

import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalFocusManager
import dev.opencode.mobile.ui.components.OpenCodeInputFocusRegistry
import kotlinx.coroutines.delay

/**
 * Window-level dispatcher for [KeyboardShortcutAction]s. Screens register a handler while the
 * shortcut applies (see [KeyboardShortcutHandler]); the Activity forwards key events before the
 * view hierarchy sees them, so Compose focus traversal never swallows Ctrl+Tab.
 */
class KeyboardShortcutHost(
    val inputFocus: OpenCodeInputFocusRegistry = OpenCodeInputFocusRegistry(),
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private class Registration(
        val action: KeyboardShortcutAction,
        val handler: () -> Unit,
    )

    private val registrations = mutableListOf<Registration>()
    private var consumedDownKeyCode: Int? = null

    private class InterruptRegistration(
        val handler: () -> Unit,
    )

    private val interruptRegistrations = mutableListOf<InterruptRegistration>()
    private var clearWindowFocus: (() -> Unit)? = null
    private var escapeDown: KeyboardEscapeAction? = null
    private var escapeBackPending = false

    /** The window's Back action (the Activity's `OnBackPressedDispatcher`), used for Esc = Back. */
    var systemBack: (() -> Unit)? = null
    private var consumedNavigationKeyCode: Int? = null

    /** True while something in the main window content holds Compose focus; see [KeyboardFocusRoot]. */
    var windowFocusHeld: Boolean = false

    /** When the first Esc armed the interrupt, or null. Observable so the hint can show. */
    var interruptArmedAtMs: Long? by mutableStateOf(null)
        private set

    /** True while an armed interrupt is still within its window and an interrupt applies. */
    val interruptArmed: Boolean
        get() = KeyboardNavigationRules.interruptArmed(interruptRegistrations.isNotEmpty(), interruptArmedAtMs, clock())

    fun now(): Long = clock()

    fun attachWindowFocus(clear: () -> Unit) {
        clearWindowFocus = clear
    }

    fun detachWindowFocus() {
        clearWindowFocus = null
        windowFocusHeld = false
    }

    /** Esc interrupts through [handler] while registered (a busy Session Chat). */
    fun registerInterrupt(handler: () -> Unit): Any = InterruptRegistration(handler).also(interruptRegistrations::add)

    fun unregisterInterrupt(registration: Any) {
        interruptRegistrations.remove(registration)
        if (interruptRegistrations.isEmpty()) interruptArmedAtMs = null
    }

    fun disarmInterrupt() {
        interruptArmedAtMs = null
    }

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

    /**
     * Standard navigation keys the window decides before the view hierarchy: Esc (see
     * [KeyboardNavigationRules.escape]) and the first Tab or arrow into a waiting prompt.
     * [dispatchToViews] runs the normal view dispatch; it is used for every key this does not
     * own, and for Esc when a surface or a focused control may handle it first.
     */
    fun handleNavigationKey(
        isDown: Boolean,
        keyCode: Int,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        meta: Boolean,
        repeatCount: Int,
        nativeTextEditorFocused: Boolean,
        dispatchToViews: () -> Boolean,
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && !ctrl && !alt && !shift && !meta) {
            return handleEscape(isDown, repeatCount, nativeTextEditorFocused, dispatchToViews)
        }
        if (!isDown) {
            if (consumedNavigationKeyCode == keyCode) {
                consumedNavigationKeyCode = null
                return true
            }
            return dispatchToViews()
        }
        val entry = inputFocus.promptEntry
        val enters =
            KeyboardNavigationRules.entersPrompt(
                keyCode = keyCode,
                ctrl = ctrl,
                alt = alt,
                meta = meta,
                promptShown = entry != null,
                focusHeld = focusHeld(nativeTextEditorFocused),
                surfaceOpen = inputFocus.hasModalSurface,
            )
        if (enters && entry != null && entry()) {
            consumedNavigationKeyCode = keyCode
            return true
        }
        return dispatchToViews()
    }

    private fun focusHeld(nativeTextEditorFocused: Boolean): Boolean =
        windowFocusHeld || nativeTextEditorFocused || inputFocus.focusedTextInput != null

    private fun handleEscape(
        isDown: Boolean,
        repeatCount: Int,
        nativeTextEditorFocused: Boolean,
        dispatchToViews: () -> Boolean,
    ): Boolean {
        if (!isDown) {
            val down = escapeDown
            val back = escapeBackPending
            escapeDown = null
            escapeBackPending = false
            if (back) {
                // Back acts on release, like the system Back key.
                systemBack?.invoke()
                return true
            }
            return when (down) {
                KeyboardEscapeAction.CloseSurface, KeyboardEscapeAction.PassThrough, null -> dispatchToViews()
                KeyboardEscapeAction.LeaveFocus -> dispatchToViews() || true
                else -> true
            }
        }
        if (repeatCount > 0) {
            return when {
                escapeBackPending -> true
                escapeDown == KeyboardEscapeAction.CloseSurface || escapeDown == KeyboardEscapeAction.PassThrough ->
                    dispatchToViews()
                else -> true
            }
        }
        val result =
            KeyboardNavigationRules.escape(
                surfaceOpen = inputFocus.hasModalSurface,
                focusHeld = focusHeld(nativeTextEditorFocused),
                canInterrupt = interruptRegistrations.isNotEmpty(),
                interruptArmedAtMs = interruptArmedAtMs,
                nowMs = clock(),
            )
        escapeDown = result.action
        interruptArmedAtMs = result.interruptArmedAtMs
        return when (result.action) {
            // The surface owns Esc (bottom sheets close on it); unhandled, Esc acts as Back.
            KeyboardEscapeAction.CloseSurface -> dispatchToViews() || backOnRelease()
            // A focused control may own Esc (a custom answer returns to its option row).
            KeyboardEscapeAction.LeaveFocus -> {
                if (!dispatchToViews()) clearWindowFocus?.invoke()
                true
            }
            KeyboardEscapeAction.Interrupt -> {
                interruptRegistrations.lastOrNull()?.handler?.invoke()
                true
            }
            KeyboardEscapeAction.ArmInterrupt -> true
            // Nothing in the app wants Esc: it backs out like Back.
            KeyboardEscapeAction.PassThrough -> dispatchToViews() || backOnRelease()
        }
    }

    /**
     * Esc = Back. Keyboard maps disagree on whether Esc falls back to Back (the virtual and some
     * emulator keyboards do not), so the host runs [systemBack] itself on release. Without one it
     * leaves the key to the system fallback.
     */
    private fun backOnRelease(): Boolean {
        if (systemBack == null) return false
        escapeBackPending = true
        return true
    }

    fun dispatchNavigationKeyEvent(
        event: KeyEvent,
        focusedView: View?,
        dispatchToViews: () -> Boolean,
    ): Boolean {
        val isDown =
            when (event.action) {
                KeyEvent.ACTION_DOWN -> true
                KeyEvent.ACTION_UP -> false
                else -> return dispatchToViews()
            }
        return handleNavigationKey(
            isDown = isDown,
            keyCode = event.keyCode,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
            meta = event.isMetaPressed,
            repeatCount = event.repeatCount,
            nativeTextEditorFocused = focusedView.isNativeTextEditor(),
            dispatchToViews = dispatchToViews,
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

/**
 * Wraps the main window content so Esc knows whether anything holds focus and can let it go.
 * Install once, around the app's navigation.
 *
 * Letting go parks focus on this invisible root instead of clearing it: with a hardware keyboard
 * Android hands cleared focus straight to the first focusable view (the Home title), so a stray
 * Return would press it. Parked focus shows nothing, and the next Tab starts from the top.
 */
@Composable
fun KeyboardFocusRoot(content: @Composable () -> Unit) {
    val host = LocalKeyboardShortcutHost.current
    val focusManager = LocalFocusManager.current
    val park = remember { FocusRequester() }
    val parkFocus: () -> Unit = {
        val parked = runCatching { park.requestFocus() }.getOrDefault(false)
        if (!parked) focusManager.clearFocus(force = true)
    }
    DisposableEffect(host, focusManager) {
        host?.attachWindowFocus(parkFocus)
        host?.inputFocus?.parkFocus = parkFocus
        onDispose {
            host?.detachWindowFocus()
            host?.inputFocus?.parkFocus = null
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onFocusChanged { host?.windowFocusHeld = it.hasFocus && !it.isFocused }
                .focusRequester(park)
                .focusTarget(),
    ) {
        content()
    }
}

/**
 * Lets Esc interrupt the running turn while [enabled] (a busy Session Chat): the first Esc arms,
 * a second within [KeyboardNavigationRules.INTERRUPT_ARM_WINDOW_MS] calls [onInterrupt]. Use
 * the same abort path as the Stop button.
 */
@Composable
fun KeyboardInterruptHandler(
    enabled: Boolean,
    onInterrupt: () -> Unit,
) {
    val host = LocalKeyboardShortcutHost.current
    val currentOnInterrupt by rememberUpdatedState(onInterrupt)
    DisposableEffect(host, enabled) {
        val registration = if (host != null && enabled) host.registerInterrupt { currentOnInterrupt() } else null
        onDispose { registration?.let { host?.unregisterInterrupt(it) } }
    }
}

/** True while the first Esc has armed the interrupt; disarms itself when the window closes. */
@Composable
fun rememberKeyboardInterruptArmed(): Boolean {
    val host = LocalKeyboardShortcutHost.current ?: return false
    val armedAt = host.interruptArmedAtMs
    LaunchedEffect(armedAt) {
        if (armedAt == null) return@LaunchedEffect
        val remaining = KeyboardNavigationRules.INTERRUPT_ARM_WINDOW_MS - (host.now() - armedAt)
        if (remaining > 0) delay(remaining)
        if (host.interruptArmedAtMs == armedAt) host.disarmInterrupt()
    }
    return armedAt != null && host.interruptArmed
}
