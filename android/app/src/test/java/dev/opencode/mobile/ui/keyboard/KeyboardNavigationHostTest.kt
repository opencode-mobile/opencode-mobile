package dev.opencode.mobile.ui.keyboard

import android.view.KeyEvent
import dev.opencode.mobile.ui.components.OpenCodeFocusedTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardNavigationHostTest {
    private var now = 1_000L
    private val host = KeyboardShortcutHost(clock = { now })
    private val calls = mutableListOf<String>()
    private var viewsHandle = false

    private fun key(
        keyCode: Int,
        isDown: Boolean,
        ctrl: Boolean = false,
        shift: Boolean = false,
        repeatCount: Int = 0,
    ): Boolean =
        host.handleNavigationKey(
            isDown = isDown,
            keyCode = keyCode,
            ctrl = ctrl,
            alt = false,
            shift = shift,
            meta = false,
            repeatCount = repeatCount,
            nativeTextEditorFocused = false,
            dispatchToViews = {
                calls += "views:${if (isDown) "down" else "up"}"
                viewsHandle
            },
        )

    private fun tap(keyCode: Int): Pair<Boolean, Boolean> = key(keyCode, isDown = true) to key(keyCode, isDown = false)

    private fun escape() = tap(KeyEvent.KEYCODE_ESCAPE)

    @Test
    fun escapeWithNothingToDoGoesToViewsSoItActsAsBack() {
        viewsHandle = false
        assertEquals(false to false, escape())
        assertEquals(listOf("views:down", "views:up"), calls)
    }

    @Test
    fun escapeWithNothingToDoRunsBackOnReleaseWhenTheWindowHasOne() {
        host.systemBack = { calls += "back" }
        assertEquals(true to true, escape())
        assertEquals(listOf("views:down", "back"), calls)
    }

    @Test
    fun escapeHandledByAViewDoesNotGoBack() {
        host.systemBack = { calls += "back" }
        viewsHandle = true
        escape()
        assertEquals(listOf("views:down", "views:up"), calls)
    }

    @Test
    fun surfaceThatDoesNotHandleEscapeClosesThroughBack() {
        host.systemBack = { calls += "back" }
        host.inputFocus.updateModalSurface(Any(), shown = true)
        escape()
        assertEquals(listOf("views:down", "back"), calls)
    }

    @Test
    fun busySessionArmWinsOverBack() {
        host.systemBack = { calls += "back" }
        host.registerInterrupt { calls += "interrupt" }
        assertEquals(true to true, escape())
        assertEquals(emptyList<String>(), calls)
        assertEquals(1_000L, host.interruptArmedAtMs)
    }

    @Test
    fun heldIdleEscapeKeepsGoingToViews() {
        key(KeyEvent.KEYCODE_ESCAPE, isDown = true)
        key(KeyEvent.KEYCODE_ESCAPE, isDown = true, repeatCount = 1)
        key(KeyEvent.KEYCODE_ESCAPE, isDown = false)
        assertEquals(listOf("views:down", "views:down", "views:up"), calls)
    }

    @Test
    fun escapeWithSurfaceGoesToViewsSoBackCanCloseIt() {
        host.inputFocus.updateModalSurface(Any(), shown = true)
        viewsHandle = false
        assertEquals(false to false, escape())
        assertEquals(listOf("views:down", "views:up"), calls)
    }

    @Test
    fun escapeClearsFocusWhenNoControlHandlesIt() {
        host.attachWindowFocus { calls += "clear" }
        host.windowFocusHeld = true
        assertEquals(true to true, escape())
        assertEquals(listOf("views:down", "clear", "views:up"), calls)
    }

    @Test
    fun escapeLetsAFocusedControlHandleItFirst() {
        host.attachWindowFocus { calls += "clear" }
        host.windowFocusHeld = true
        viewsHandle = true
        escape()
        assertEquals(listOf("views:down", "views:up"), calls)
    }

    @Test
    fun focusedComposerCountsAsFocus() {
        host.attachWindowFocus { calls += "clear" }
        host.inputFocus.updateTextInput(Any(), OpenCodeFocusedTextInput.SessionComposer, focused = true)
        host.registerInterrupt { calls += "interrupt" }
        escape()
        assertEquals(listOf("views:down", "clear", "views:up"), calls)
        assertNull(host.interruptArmedAtMs)
    }

    @Test
    fun twoEscapesWithinWindowInterrupt() {
        host.registerInterrupt { calls += "interrupt" }
        escape()
        assertEquals(1_000L, host.interruptArmedAtMs)
        assertTrue(host.interruptArmed)
        assertEquals(emptyList<String>(), calls)
        now += 4_000L
        escape()
        assertEquals(listOf("interrupt"), calls)
        assertNull(host.interruptArmedAtMs)
    }

    @Test
    fun escapeAfterWindowArmsAgain() {
        host.registerInterrupt { calls += "interrupt" }
        escape()
        now += 5_001L
        assertFalse(host.interruptArmed)
        escape()
        assertEquals(emptyList<String>(), calls)
        assertEquals(6_001L, host.interruptArmedAtMs)
    }

    @Test
    fun sessionGoingIdleDisarms() {
        val registration = host.registerInterrupt { calls += "interrupt" }
        escape()
        host.unregisterInterrupt(registration)
        assertNull(host.interruptArmedAtMs)
        host.registerInterrupt { calls += "interrupt" }
        escape()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun escapeConsumedByFocusDisarms() {
        host.registerInterrupt { calls += "interrupt" }
        escape()
        host.attachWindowFocus { calls += "clear" }
        host.windowFocusHeld = true
        escape()
        host.windowFocusHeld = false
        escape()
        assertFalse(calls.contains("interrupt"))
        assertEquals(1_000L, host.interruptArmedAtMs)
    }

    @Test
    fun heldEscapeDoesNotRepeatTheInterrupt() {
        host.registerInterrupt { calls += "interrupt" }
        key(KeyEvent.KEYCODE_ESCAPE, isDown = true)
        key(KeyEvent.KEYCODE_ESCAPE, isDown = true, repeatCount = 1)
        key(KeyEvent.KEYCODE_ESCAPE, isDown = true, repeatCount = 2)
        key(KeyEvent.KEYCODE_ESCAPE, isDown = false)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun firstTabMovesIntoAWaitingPrompt() {
        host.inputFocus.updatePromptEntry(Any()) {
            calls += "enter"
            true
        }
        assertEquals(true to true, tap(KeyEvent.KEYCODE_TAB))
        assertEquals(listOf("enter"), calls)
    }

    @Test
    fun arrowMovesIntoAWaitingPrompt() {
        host.inputFocus.updatePromptEntry(Any()) {
            calls += "enter"
            true
        }
        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf("enter"), calls)
    }

    @Test
    fun returnNeverReachesAWaitingPrompt() {
        host.inputFocus.updatePromptEntry(Any()) {
            calls += "enter"
            true
        }
        tap(KeyEvent.KEYCODE_ENTER)
        tap(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf("views:down", "views:up", "views:down", "views:up"), calls)
    }

    @Test
    fun tabFollowsNormalOrderOnceFocusIsHeld() {
        host.inputFocus.updatePromptEntry(Any()) {
            calls += "enter"
            true
        }
        host.windowFocusHeld = true
        tap(KeyEvent.KEYCODE_TAB)
        assertEquals(listOf("views:down", "views:up"), calls)
    }

    @Test
    fun withoutPromptTabGoesToViews() {
        tap(KeyEvent.KEYCODE_TAB)
        assertEquals(listOf("views:down", "views:up"), calls)
    }

    @Test
    fun removedPromptNoLongerTakesTab() {
        val owner = Any()
        host.inputFocus.updatePromptEntry(owner) {
            calls += "enter"
            true
        }
        host.inputFocus.updatePromptEntry(owner, null)
        tap(KeyEvent.KEYCODE_TAB)
        assertEquals(listOf("views:down", "views:up"), calls)
    }
}
