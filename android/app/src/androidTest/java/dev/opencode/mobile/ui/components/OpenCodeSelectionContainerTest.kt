package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Foundation 1.12 saves a backwards selection with a wrong "handles crossed" flag
 * when its end was dragged up within one Text and past that Text's right edge.
 * Restoring it draws start > end and throws IllegalArgumentException from
 * MultiParagraph.getPathForRange (#1042). [OpenCodeSelectionContainer] does not
 * restore a selection, so nothing is drawn.
 *
 * The case without the wrapper is skipped unless the instrumentation argument
 * `reproduce1042=true` is passed; it is expected to crash on Foundation 1.12.
 */
class OpenCodeSelectionContainerTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun restoredBackwardsSelection_doesNotCrash() {
        selectBackwardsPastRightEdgeThenRestore { content ->
            OpenCodeSelectionContainer(Modifier.fillMaxWidth().testTag(CONTAINER), content)
        }

        assertEquals("selection is not restored", 0, selectionHandles().size)
    }

    @Test
    fun restoredBackwardsSelection_withoutWrapper_reproducesCrash() {
        assumeTrue(
            "pass -e reproduce1042 true to run",
            InstrumentationRegistry.getArguments().getString("reproduce1042") == "true",
        )

        // Expected on Foundation 1.12: IllegalArgumentException
        // "Start(..) or End(..) is out of range" from SelectionController.draw.
        selectBackwardsPastRightEdgeThenRestore { content ->
            SelectionContainer(Modifier.fillMaxWidth().testTag(CONTAINER), content)
        }
    }

    private fun selectBackwardsPastRightEdgeThenRestore(container: @Composable (content: @Composable () -> Unit) -> Unit) {
        var layout: TextLayoutResult? = null
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            container {
                Text(
                    text = TEXT,
                    style = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
                    onTextLayout = { layout = it },
                    // Narrower than the container, so a point can be past the
                    // Text's right edge and still inside the container.
                    modifier = Modifier.fillMaxWidth().padding(end = 48.dp),
                )
            }
        }
        composeTestRule.waitForIdle()
        val textLayout = checkNotNull(layout)
        val wordOnLine2 = textLayout.getBoundingBox(TEXT.indexOf("five") + 1).center
        val line1Y = (textLayout.getLineTop(0) + textLayout.getLineBottom(0)) / 2f

        composeTestRule.onNodeWithTag(CONTAINER).performTouchInput {
            val pastRightEdge = Offset(width - 24.dp.toPx(), line1Y)
            down(wordOnLine2)
            moveBy(Offset.Zero, delayMillis = viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(wordOnLine2.x, line1Y))
            moveTo(Offset((wordOnLine2.x + pastRightEdge.x) / 2f, line1Y))
            moveTo(pastRightEdge)
            up()
        }
        composeTestRule.waitForIdle()
        assertEquals("selection exists before restore", 2, selectionHandles().size)

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()
    }

    private fun selectionHandles(): List<SemanticsNode> =
        composeTestRule
            .onAllNodes(isSelectionHandle, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)

    private companion object {
        const val CONTAINER = "container"
        const val TEXT = "one two three\nfour five six\nseven"

        // Foundation's SelectionHandleInfoKey is internal; match it by name.
        val isSelectionHandle =
            SemanticsMatcher("is selection handle") { node ->
                node.config.any { (key, _) -> key.name == "SelectionHandleInfo" }
            }
    }
}
