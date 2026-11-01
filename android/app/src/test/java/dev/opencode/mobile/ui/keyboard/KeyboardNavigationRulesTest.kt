package dev.opencode.mobile.ui.keyboard

import android.content.res.Configuration
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardNavigationRulesTest {
    private fun escape(
        surfaceOpen: Boolean = false,
        focusHeld: Boolean = false,
        canInterrupt: Boolean = false,
        armedAt: Long? = null,
        now: Long = 10_000L,
    ) = KeyboardNavigationRules.escape(surfaceOpen, focusHeld, canInterrupt, armedAt, now)

    @Test
    fun escapeClosesSurfaceFirst() {
        val result = escape(surfaceOpen = true, focusHeld = true, canInterrupt = true, armedAt = 9_000L)
        assertEquals(KeyboardEscapeAction.CloseSurface, result.action)
        assertNull(result.interruptArmedAtMs)
    }

    @Test
    fun escapeLeavesFocusBeforeInterrupting() {
        val result = escape(focusHeld = true, canInterrupt = true, armedAt = 9_000L)
        assertEquals(KeyboardEscapeAction.LeaveFocus, result.action)
        assertNull(result.interruptArmedAtMs)
    }

    @Test
    fun escapePassesThroughWhenIdleSoItActsAsBack() {
        val result = escape()
        assertEquals(KeyboardEscapeAction.PassThrough, result.action)
        assertNull(result.interruptArmedAtMs)
    }

    @Test
    fun firstEscapeOnBusySessionArms() {
        val result = escape(canInterrupt = true, now = 10_000L)
        assertEquals(KeyboardEscapeAction.ArmInterrupt, result.action)
        assertEquals(10_000L, result.interruptArmedAtMs)
    }

    @Test
    fun secondEscapeWithinWindowInterrupts() {
        val result = escape(canInterrupt = true, armedAt = 10_000L, now = 14_999L)
        assertEquals(KeyboardEscapeAction.Interrupt, result.action)
        assertNull(result.interruptArmedAtMs)
    }

    @Test
    fun secondEscapeJustInsideWindowInterrupts() {
        val result = escape(canInterrupt = true, armedAt = 10_000L, now = 14_999L)
        assertEquals(KeyboardEscapeAction.Interrupt, result.action)
    }

    @Test
    fun secondEscapeAtExactlyFiveSecondsArmsAgain() {
        // The window is strictly shorter than 5 s.
        val result = escape(canInterrupt = true, armedAt = 10_000L, now = 15_000L)
        assertEquals(KeyboardEscapeAction.ArmInterrupt, result.action)
        assertEquals(15_000L, result.interruptArmedAtMs)
    }

    @Test
    fun escapeAfterExpiredArmArmsAgain() {
        val result = escape(canInterrupt = true, armedAt = 10_000L, now = 15_001L)
        assertEquals(KeyboardEscapeAction.ArmInterrupt, result.action)
        assertEquals(15_001L, result.interruptArmedAtMs)
    }

    @Test
    fun armFromTheFutureDoesNotInterrupt() {
        // A clock that went backwards (or a stale arm) never counts as armed.
        val result = escape(canInterrupt = true, armedAt = 20_000L, now = 10_000L)
        assertEquals(KeyboardEscapeAction.ArmInterrupt, result.action)
    }

    @Test
    fun armDoesNotApplyOnceSessionIsIdle() {
        assertFalse(KeyboardNavigationRules.interruptArmed(canInterrupt = false, interruptArmedAtMs = 10_000L, nowMs = 10_001L))
        assertEquals(KeyboardEscapeAction.PassThrough, escape(canInterrupt = false, armedAt = 10_000L, now = 10_001L).action)
    }

    @Test
    fun interruptArmedOnlyInsideWindow() {
        assertTrue(KeyboardNavigationRules.interruptArmed(true, 1_000L, 1_000L))
        assertTrue(KeyboardNavigationRules.interruptArmed(true, 1_000L, 5_999L))
        assertFalse(KeyboardNavigationRules.interruptArmed(true, 1_000L, 6_000L))
        assertFalse(KeyboardNavigationRules.interruptArmed(true, null, 1_000L))
    }

    private fun composerReturn(
        isReturn: Boolean = true,
        hardware: Boolean = true,
        shift: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
        composing: Boolean = false,
        sendEnabled: Boolean = true,
        suggestionsOpen: Boolean = false,
    ) = KeyboardNavigationRules.composerReturn(isReturn, hardware, shift, ctrl, alt, meta, composing, sendEnabled, suggestionsOpen)

    @Test
    fun returnWithSuggestionsOpenNeverSends() {
        assertEquals(ComposerReturnAction.Ignore, composerReturn(suggestionsOpen = true))
        // Shift+Return still adds a line; composition still goes to the IME.
        assertEquals(ComposerReturnAction.PassThrough, composerReturn(suggestionsOpen = true, shift = true))
        assertEquals(ComposerReturnAction.PassThrough, composerReturn(suggestionsOpen = true, composing = true))
    }

    @Test
    fun liveSendEnabledFollowsTheFieldText() {
        // Same text as the screen state: the screen decides.
        assertTrue(KeyboardNavigationRules.liveSendEnabled(stateSendEnabled = true, stateText = "hi", liveText = "hi"))
        assertFalse(KeyboardNavigationRules.liveSendEnabled(stateSendEnabled = false, stateText = "hi", liveText = "hi"))
        // The field is ahead of the state: text the IME just committed can be sent.
        assertTrue(KeyboardNavigationRules.liveSendEnabled(stateSendEnabled = false, stateText = "", liveText = "hello"))
        // The field was just cleared: nothing to send even if the state still says so.
        assertFalse(KeyboardNavigationRules.liveSendEnabled(stateSendEnabled = true, stateText = "hi", liveText = "  "))
    }

    @Test
    fun focusParksWhenANewRequestArrivesWhileTheComposerHasFocus() {
        fun parks(
            previous: String?,
            current: String?,
            hasFocus: Boolean = true,
        ) = KeyboardNavigationRules.parksFocusOnPromptChange(previous, current, hasFocus)
        // Composer to prompt, and one queued request to the next.
        assertTrue(parks(previous = null, current = "permission:a"))
        assertTrue(parks(previous = "permission:a", current = "permission:b"))
        assertTrue(parks(previous = "permission:a", current = "question:q"))
        // Same request (a question's next step), back to the composer, or nothing focused.
        assertFalse(parks(previous = "question:q", current = "question:q"))
        assertFalse(parks(previous = "permission:a", current = null))
        assertFalse(parks(previous = "permission:a", current = "permission:b", hasFocus = false))
    }

    @Test
    fun hardwareReturnSendsWhenSendIsEnabled() {
        assertEquals(ComposerReturnAction.Send, composerReturn())
    }

    @Test
    fun hardwareReturnIsSwallowedWhenSendIsDisabled() {
        assertEquals(ComposerReturnAction.Ignore, composerReturn(sendEnabled = false))
    }

    @Test
    fun shiftReturnAddsALine() {
        assertEquals(ComposerReturnAction.PassThrough, composerReturn(shift = true))
        assertEquals(ComposerReturnAction.PassThrough, composerReturn(shift = true, sendEnabled = false))
    }

    @Test
    fun softKeyboardReturnKeepsItsBehaviour() {
        assertEquals(ComposerReturnAction.PassThrough, composerReturn(hardware = false))
    }

    @Test
    fun returnWhileComposingCommitsInsteadOfSending() {
        assertEquals(ComposerReturnAction.PassThrough, composerReturn(composing = true))
    }

    @Test
    fun modifiedReturnIsLeftAlone() {
        assertEquals(ComposerReturnAction.PassThrough, composerReturn(ctrl = true))
        assertEquals(ComposerReturnAction.PassThrough, composerReturn(alt = true))
        assertEquals(ComposerReturnAction.PassThrough, composerReturn(meta = true))
    }

    @Test
    fun otherKeysPassThrough() {
        assertEquals(ComposerReturnAction.PassThrough, composerReturn(isReturn = false))
    }

    @Test
    fun softKeyboardFlagMeansNotHardware() {
        assertTrue(KeyboardNavigationRules.fromHardwareKeyboard(hardwareKeyboardConnected = true, eventFlags = 0))
        assertFalse(KeyboardNavigationRules.fromHardwareKeyboard(true, KeyEvent.FLAG_SOFT_KEYBOARD))
        assertFalse(KeyboardNavigationRules.fromHardwareKeyboard(false, 0))
    }

    @Test
    fun customAnswerReturnSavesAndEscapeLeaves() {
        fun key(
            isReturn: Boolean = false,
            isEscape: Boolean = false,
            hardware: Boolean = true,
            shift: Boolean = false,
            ctrl: Boolean = false,
        ) = KeyboardNavigationRules.customAnswerKey(isReturn, isEscape, hardware, shift, ctrl, alt = false, meta = false)
        assertEquals(CustomAnswerKeyAction.Save, key(isReturn = true))
        assertEquals(CustomAnswerKeyAction.PassThrough, key(isReturn = true, shift = true))
        assertEquals(CustomAnswerKeyAction.PassThrough, key(isReturn = true, hardware = false))
        assertEquals(CustomAnswerKeyAction.Leave, key(isEscape = true))
        assertEquals(CustomAnswerKeyAction.PassThrough, key(isEscape = true, ctrl = true))
        assertEquals(CustomAnswerKeyAction.PassThrough, key())
    }

    @Test
    fun hardwareTabLeavesAMultiLineField() {
        fun tab(
            isTab: Boolean = true,
            hardware: Boolean = true,
            shift: Boolean = false,
            ctrl: Boolean = false,
        ) = KeyboardNavigationRules.textFieldTab(isTab, hardware, shift, ctrl, alt = false, meta = false)
        assertEquals(TextFieldTabAction.Next, tab())
        assertEquals(TextFieldTabAction.Previous, tab(shift = true))
        // Ctrl+Tab is Next Session; soft keyboards and other keys keep their behaviour.
        assertEquals(TextFieldTabAction.PassThrough, tab(ctrl = true))
        assertEquals(TextFieldTabAction.PassThrough, tab(hardware = false))
        assertEquals(TextFieldTabAction.PassThrough, tab(isTab = false))
    }

    @Test
    fun promptHintNeedsHardwareKeyboardAndFocusOutside() {
        assertTrue(KeyboardNavigationRules.promptHintVisible(hardwareKeyboardConnected = true, focusInsidePrompt = false))
        assertFalse(KeyboardNavigationRules.promptHintVisible(hardwareKeyboardConnected = true, focusInsidePrompt = true))
        assertFalse(KeyboardNavigationRules.promptHintVisible(hardwareKeyboardConnected = false, focusInsidePrompt = false))
    }

    @Test
    fun promptHintText() {
        assertEquals("Press Tab to answer with the keyboard", KeyboardNavigationRules.PROMPT_KEYBOARD_HINT)
        assertEquals("Esc again to interrupt", KeyboardNavigationRules.INTERRUPT_ARMED_HINT)
    }

    @Test
    fun promptEntryLandsOnTheFirstEnabledControl() {
        // Permission: Deny, Always, Allow once.
        assertEquals(0, KeyboardNavigationRules.promptInitialTarget(listOf(true, true, true)))
        assertEquals(1, KeyboardNavigationRules.promptInitialTarget(listOf(false, true, true)))
        assertEquals(2, KeyboardNavigationRules.promptInitialTarget(listOf(false, false, true)))
        // Question options.
        assertEquals(0, KeyboardNavigationRules.promptInitialTarget(listOf(true)))
    }

    @Test
    fun promptWithNothingEnabledIsNotEntered() {
        assertNull(KeyboardNavigationRules.promptInitialTarget(listOf(false, false, false)))
        assertNull(KeyboardNavigationRules.promptInitialTarget(listOf(false)))
        assertNull(KeyboardNavigationRules.promptInitialTarget(emptyList()))
    }

    private fun enters(
        keyCode: Int,
        ctrl: Boolean = false,
        promptShown: Boolean = true,
        focusHeld: Boolean = false,
        surfaceOpen: Boolean = false,
    ) = KeyboardNavigationRules.entersPrompt(keyCode, ctrl, alt = false, meta = false, promptShown, focusHeld, surfaceOpen)

    @Test
    fun tabAndArrowsEnterAWaitingPrompt() {
        assertTrue(enters(KeyEvent.KEYCODE_TAB))
        assertTrue(enters(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(enters(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(enters(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(enters(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun strayKeysNeverEnterAPrompt() {
        assertFalse(enters(KeyEvent.KEYCODE_ENTER))
        assertFalse(enters(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertFalse(enters(KeyEvent.KEYCODE_SPACE))
        assertFalse(enters(KeyEvent.KEYCODE_A))
        assertFalse(enters(KeyEvent.KEYCODE_Y))
        assertFalse(enters(KeyEvent.KEYCODE_1))
    }

    @Test
    fun promptEntryNeedsNothingFocusedAndNoSurface() {
        assertFalse(enters(KeyEvent.KEYCODE_TAB, promptShown = false))
        assertFalse(enters(KeyEvent.KEYCODE_TAB, focusHeld = true))
        assertFalse(enters(KeyEvent.KEYCODE_TAB, surfaceOpen = true))
        // Ctrl+Tab is Next Session.
        assertFalse(enters(KeyEvent.KEYCODE_TAB, ctrl = true))
    }

    @Test
    fun hardwareKeyboardFromConfiguration() {
        assertTrue(
            KeyboardNavigationRules.hardwareKeyboardConnected(
                Configuration.KEYBOARD_QWERTY,
                Configuration.HARDKEYBOARDHIDDEN_NO,
            ),
        )
        assertFalse(
            KeyboardNavigationRules.hardwareKeyboardConnected(
                Configuration.KEYBOARD_QWERTY,
                Configuration.HARDKEYBOARDHIDDEN_YES,
            ),
        )
        assertFalse(
            KeyboardNavigationRules.hardwareKeyboardConnected(
                Configuration.KEYBOARD_NOKEYS,
                Configuration.HARDKEYBOARDHIDDEN_NO,
            ),
        )
        assertFalse(
            KeyboardNavigationRules.hardwareKeyboardConnected(
                Configuration.KEYBOARD_UNDEFINED,
                Configuration.HARDKEYBOARDHIDDEN_UNDEFINED,
            ),
        )
    }
}
