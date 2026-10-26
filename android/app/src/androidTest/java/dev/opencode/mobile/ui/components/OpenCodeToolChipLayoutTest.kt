package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalLayoutApi::class)
class OpenCodeToolChipLayoutTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tappableChipInZeroWidthFlowRowMeasuresWithoutCrashing() {
        setChipInSlot(0.dp)

        assertEquals(0f, chipWidth().value, 0.5f)
    }

    @Test
    fun tappableChipNarrowerThanTouchTargetKeepsItsSlotWidth() {
        setChipInSlot(30.dp)

        assertEquals(30f, chipWidth().value, 0.5f)
    }

    @Test
    fun tappableChipInWideSlotKeepsItsVisualHeight() {
        setChipInSlot(320.dp)

        val bounds = composeTestRule.onNodeWithTag(CHIP_TAG).getUnclippedBoundsInRoot()
        assertEquals(36f, (bounds.bottom - bounds.top).value, 0.5f)
    }

    private fun setChipInSlot(width: Dp) {
        composeTestRule.setContent {
            Box(Modifier.requiredWidth(width)) {
                FlowRow(Modifier.fillMaxWidth()) {
                    OpenCodeToolChip(
                        variant = OpenCodeToolChipVariant.More(3),
                        onClick = {},
                        modifier = Modifier.testTag(CHIP_TAG),
                    )
                }
            }
        }
    }

    private fun chipWidth(): Dp {
        val bounds = composeTestRule.onNodeWithTag(CHIP_TAG).getUnclippedBoundsInRoot()
        return bounds.right - bounds.left
    }

    private companion object {
        const val CHIP_TAG = "tool-chip"
    }
}
