package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FileNavigationPillTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun exposesStableSummaryAndDispatchesOnlyEnabledDirections() {
        var previousCount = 0
        var nextCount = 0
        composeTestRule.setContent {
            OpenCodeFileNavigationPill(
                additions = 0,
                deletions = 3,
                previousEnabled = false,
                nextEnabled = true,
                onPrevious = { previousCount += 1 },
                onNext = { nextCount += 1 },
            )
        }

        composeTestRule.onNodeWithText("+0 −3").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("0 additions, 3 deletions").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Previous file").assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithContentDescription("Next file").assertIsEnabled().performClick()

        composeTestRule.runOnIdle {
            assertEquals(0, previousCount)
            assertEquals(1, nextCount)
        }
    }

    @Test
    fun overallDisabledBlocksBothDirections() {
        var clickCount = 0
        composeTestRule.setContent {
            OpenCodeFileNavigationPill(
                additions = 12,
                deletions = 0,
                previousEnabled = true,
                nextEnabled = true,
                enabled = false,
                onPrevious = { clickCount += 1 },
                onNext = { clickCount += 1 },
            )
        }

        composeTestRule.onNodeWithContentDescription("Previous file").assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithContentDescription("Next file").assertIsNotEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(0, clickCount) }
    }
}
