package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HunkNavigationPillTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dispatchesOnlyEnabledActionsAndUsesContextModeLabel() {
        var previousCount = 0
        var contextCount = 0
        var nextCount = 0
        composeTestRule.setContent {
            OpenCodeHunkNavigationPill(
                previousEnabled = false,
                nextEnabled = true,
                contextEnabled = true,
                contextExpanded = false,
                onPrevious = { previousCount += 1 },
                onToggleContext = { contextCount += 1 },
                onNext = { nextCount += 1 },
            )
        }

        composeTestRule.onNodeWithContentDescription("Previous hunk").assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithContentDescription("Expand context").assertIsEnabled().performClick()
        composeTestRule.onNodeWithContentDescription("Next hunk").assertIsEnabled().performClick()
        composeTestRule.runOnIdle {
            assertEquals(0, previousCount)
            assertEquals(1, contextCount)
            assertEquals(1, nextCount)
        }
    }

    @Test
    fun noExpandableContextDisablesCenterAction() {
        composeTestRule.setContent {
            OpenCodeHunkNavigationPill(true, false, false, true, {}, {}, {})
        }
        composeTestRule.onNodeWithContentDescription("Previous hunk").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Collapse context").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Next hunk").assertIsNotEnabled()
    }
}
