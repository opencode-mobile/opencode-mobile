package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OpenCodeFloatingPillSemanticsTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun customLabelReplacesTheVisibleLabelAndKeepsTheClick() {
        var clicks = 0
        composeTestRule.setContent {
            OpenCodeFloatingPill(
                layout = OpenCodeFloatingPillLayout.Counter("general needs input · 2 running", waiting = true),
                state = OpenCodeFloatingPillState.NeedsAttention,
                accessibilityLabel = "general needs input, 2 subagents running",
                onClick = { clicks += 1 },
            )
        }

        composeTestRule
            .onNodeWithContentDescription("general needs input, 2 subagents running")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("general needs input, 2 subagents running")))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            .performClick()
        composeTestRule.onAllNodesWithText("general needs input · 2 running").assertCountEquals(0)
        composeTestRule.runOnIdle { assertEquals(1, clicks) }
    }
}
