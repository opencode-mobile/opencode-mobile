package dev.opencode.mobile.platform

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.bottomCenter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A mouse press below the last Text in a SelectionContainer that holds two or
 * more Texts throws IndexOutOfBoundsException in Foundation 1.12
 * (MultiSelectionLayout.crossStatus). Assistant messages end in code blocks
 * and padding, so a tablet with a mouse hits this on a plain click.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeFoundationWorkaroundsTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun applyWorkarounds() {
        ComposeFoundationWorkarounds.apply()
    }

    @Test
    fun mousePressBelowLastText_doesNotCrash() {
        composeTestRule.setContent {
            SelectionContainer(Modifier.testTag("container")) {
                Column {
                    Text("First paragraph")
                    Text("Second paragraph")
                    Spacer(Modifier.height(120.dp))
                }
            }
        }

        composeTestRule.onNodeWithTag("container").performMouseInput {
            moveTo(bottomCenter.copy(y = bottomCenter.y - 10f))
            press()
            release()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Second paragraph").assertExists()
    }
}
