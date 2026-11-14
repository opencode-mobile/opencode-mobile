package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OpenCodeFloatingPillMorphTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun foldingToCompactNarrowsOnePillThroughTheInBetweenWidths() {
        var compact by mutableStateOf(false)
        composeTestRule.setContent {
            OpenCodeFloatingPill(
                layout = OpenCodeFloatingPillLayout.Closable("Request failed"),
                state = OpenCodeFloatingPillState.Error,
                compact = compact,
                onClick = {},
                onClose = {},
                modifier = Modifier.testTag(PILL),
            )
        }
        val fullWidth = pillWidth()

        composeTestRule.mainClock.autoAdvance = false
        compact = true
        composeTestRule.mainClock.advanceTimeBy(HALF_FOLD_MS)
        val midWidth = pillWidth()
        // One pill folding, not a second pill fading in over the first.
        composeTestRule.onAllNodesWithContentDescription("Request failed, Error").assertCountEquals(1)

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        val compactWidth = pillWidth()

        assertTrue("full $fullWidth > mid $midWidth > compact $compactWidth", fullWidth > midWidth && midWidth > compactWidth)
    }

    private fun pillWidth(): Dp = composeTestRule.onNodeWithTag(PILL).getBoundsInRoot().let { it.right - it.left }

    private companion object {
        const val PILL = "pill"
        const val HALF_FOLD_MS = 130L
    }
}
