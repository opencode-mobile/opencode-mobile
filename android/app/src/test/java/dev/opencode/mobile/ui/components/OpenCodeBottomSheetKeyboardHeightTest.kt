package dev.opencode.mobile.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeBottomSheetKeyboardHeightTest {
    private val resting = 400.dp
    private val available = 860.dp
    private val keyboard = 320.dp

    private fun height(
        ime: Dp,
        settled: Dp = keyboard,
        mode: OpenCodeBottomSheetContentMode = OpenCodeBottomSheetContentMode.Form,
        behavior: OpenCodeBottomSheetKeyboardBehavior = OpenCodeBottomSheetKeyboardBehavior.Expand,
        restingHeight: Dp = resting,
    ): Dp =
        openCodeBottomSheetKeyboardHeight(
            restingHeight = restingHeight,
            availableHeight = available,
            imeHeight = ime,
            settledImeHeight = settled,
            contentMode = mode,
            behavior = behavior,
        )

    private fun assertDp(
        expected: Dp,
        actual: Dp,
    ) = assertEquals(expected.value, actual.value, 0.01f)

    @Test
    fun keepHeight_ignoresTheKeyboard() {
        assertDp(resting, height(ime = keyboard, behavior = OpenCodeBottomSheetKeyboardBehavior.KeepHeight))
        assertDp(resting, height(ime = 120.dp, behavior = OpenCodeBottomSheetKeyboardBehavior.KeepHeight))
    }

    @Test
    fun expand_keepsRestingHeightWhileKeyboardIsHidden() {
        assertDp(resting, height(ime = 0.dp, settled = 0.dp))
        assertDp(resting, height(ime = 0.dp))
    }

    @Test
    fun expand_fillsSpaceAboveTheSettledKeyboard() {
        assertDp(available - keyboard, height(ime = keyboard))
    }

    @Test
    fun expand_followsKeyboardProgressMonotonically() {
        assertDp(resting + (available - keyboard - resting) / 2f, height(ime = keyboard / 2f))
        val frames = (0..20).map { step -> height(ime = keyboard * (step / 20f)) }
        assertTrue(frames.zipWithNext().all { (a, b) -> b >= a })
        assertDp(resting, frames.first())
        assertDp(available - keyboard, frames.last())
    }

    @Test
    fun expand_shrinksBelowRestingWhenTheKeyboardLeavesLessRoom() {
        assertDp(available - 700.dp, height(ime = 700.dp, settled = 700.dp))
        assertDp(0.dp, height(ime = 900.dp, settled = 900.dp))
    }

    @Test
    fun expand_appliesOnlyToFormSheets() {
        assertDp(resting, height(ime = keyboard, mode = OpenCodeBottomSheetContentMode.ActionList))
    }

    @Test
    fun keyboardLift_restsSheetAboveTheKeyboardForBothNavigationModes() {
        // Gestural: the sheet pads only by its own inset, so lift by the whole keyboard.
        assertDp(keyboard, openCodeBottomSheetKeyboardLift(keyboard, 24.dp, threeButtonNavigation = false))
        // Three-button: the sheet already pads by the navigation bar the keyboard inset includes.
        assertDp(keyboard - 48.dp, openCodeBottomSheetKeyboardLift(keyboard, 48.dp, threeButtonNavigation = true))
        assertDp(0.dp, openCodeBottomSheetKeyboardLift(20.dp, 48.dp, threeButtonNavigation = true))
        assertDp(0.dp, openCodeBottomSheetKeyboardLift(0.dp, 24.dp, threeButtonNavigation = false))
    }
}
