package dev.opencode.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodePointerRulesTest {
    @Test
    fun aRightClickDoesNotClickAControl() {
        assertTrue(OpenCodePointerRules.controlSwallowsPress(primaryPressed = false, secondaryPressed = true))
    }

    @Test
    fun aPrimaryClickOrTapStillClicks() {
        assertFalse(OpenCodePointerRules.controlSwallowsPress(primaryPressed = true, secondaryPressed = false))
        // Touch sets no mouse buttons.
        assertFalse(OpenCodePointerRules.controlSwallowsPress(primaryPressed = false, secondaryPressed = false))
    }

    @Test
    fun aSecondaryButtonAddedDuringAPrimaryPressIsNotSwallowed() {
        assertFalse(OpenCodePointerRules.controlSwallowsPress(primaryPressed = true, secondaryPressed = true))
    }
}
