package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A left drag reveals the time, unless it starts on a sideways scroller, which keeps it. */
class OpenCodeTimeRevealGestureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val time = OpenCodeMessageTime(time = "9:05 PM", day = null)
    private lateinit var scrollState: ScrollState

    private fun setContent() {
        rule.setContent {
            OpenCodeTheme {
                scrollState = rememberScrollState()
                OpenCodeTimeReveal(time = time, modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
                    Column {
                        Text("Plain text line", modifier = Modifier.testTag("text"))
                        Row(Modifier.horizontalScroll(scrollState).testTag("scroller")) {
                            Text("val total = invoices.sumOf { it.amountCents } + taxes.sumOf { it.amountCents } + fees")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun leftDragOnText_revealsTime() {
        setContent()
        rule.onNodeWithTag("text").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(-12f * density, 0f)) }
        }
        rule.onNodeWithText("9:05 PM", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("text").performTouchInput { up() }
    }

    @Test
    fun leftDragOnSidewaysScroller_scrollsItAndKeepsTimeHidden() {
        setContent()
        rule.onNodeWithTag("scroller").performTouchInput {
            down(center)
            // A slightly diagonal start passes the reveal's 8 dp direction check while the
            // horizontal travel is still under the scroller's touch slop.
            repeat(11) { moveBy(Offset(-0.7f * density, -0.4f * density)) }
            repeat(8) { moveBy(Offset(-12f * density, 0f)) }
        }
        rule.onNodeWithText("9:05 PM", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("scroller").performTouchInput { up() }
        rule.waitForIdle()
        assertTrue("code scrolled", scrollState.value > 0)
    }
}
