package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReviewChangesPillTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun exposesPluralizedAccessibilityAndBlocksDisabledClick() {
        var clickCount = 0
        composeTestRule.setContent {
            OpenCodeReviewChangesPill(
                variant = OpenCodeReviewChangesPillVariant.LatestTurn,
                changedFileCount = 1,
                additions = 12,
                deletions = 3,
                onClick = { clickCount += 1 },
            )
            OpenCodeReviewChangesPill(
                variant = OpenCodeReviewChangesPillVariant.LatestTurn,
                changedFileCount = 4,
                additions = 0,
                deletions = 0,
                enabled = false,
                onClick = { clickCount += 1 },
            )
        }

        composeTestRule
            .onNodeWithContentDescription("Review changes, 1 file, 12 additions, 3 deletions")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeTestRule
            .onNodeWithContentDescription("Review changes, 4 files")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()

        composeTestRule.runOnIdle { assertEquals(1, clickCount) }
    }

    @Test
    fun centersSummaryVerticallyInsidePill() {
        composeTestRule.setContent {
            OpenCodeReviewChangesPill(
                variant = OpenCodeReviewChangesPillVariant.LatestTurn,
                changedFileCount = 5,
                additions = 85,
                deletions = 34,
                onClick = {},
            )
        }

        val pillBounds =
            composeTestRule
                .onNodeWithContentDescription("Review changes, 5 files, 85 additions, 34 deletions")
                .getUnclippedBoundsInRoot()
        val textBounds =
            composeTestRule
                .onNodeWithText("5 files  +85  −34", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()

        val pillCenterY = ((pillBounds.top + pillBounds.bottom) / 2).value
        val textCenterY = ((textBounds.top + textBounds.bottom) / 2).value
        assertEquals(pillCenterY, textCenterY, 0.5f)
    }
}
