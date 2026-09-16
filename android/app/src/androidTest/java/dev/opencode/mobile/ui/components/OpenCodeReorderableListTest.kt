package dev.opencode.mobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import dev.opencode.mobile.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Tap, long-press-and-drag, and long-press-and-release have to stay separable
 * on one row. Every case here keeps [onRemoveProject] set so the rows take the
 * lift-on-long-press path, which is the one the three gestures share.
 */
class OpenCodeReorderableListTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val tabs =
        listOf(
            SessionListFilterTab.all(projectCount = 2, sessionCount = 3),
            SessionListFilterTab.project("/playground", "playground", count = 2),
            SessionListFilterTab.project("/website", "website", count = 1),
        )

    private var selected: String? = null
    private var removed: String? = null
    private var reordered: List<String>? = null

    private fun setPicker() {
        composeTestRule.setContent {
            OpenCodeTheme {
                // The bottom sheet puts a no-op clickable around the rows; it consumes
                // the same pointer, so it belongs in the repro.
                Box(Modifier.clickable {}) {
                    SessionListProjectPickerRows(
                        tabs = tabs,
                        onSelect = { selected = it },
                        onRemoveProject = { removed = it },
                        onReorderProjects = { reordered = it },
                    )
                }
            }
        }
    }

    @Test
    fun tapSelectsProject_whenParentClickableConsumesTheRelease() {
        setPicker()

        composeTestRule.onNodeWithText("playground").performTouchInput { click() }

        composeTestRule.runOnIdle {
            assertEquals("/playground", selected)
            assertNull(removed)
            assertNull(reordered)
        }
    }

    @Test
    fun longPressAndReleaseWithoutDraggingRemovesProject() {
        setPicker()

        composeTestRule.onNodeWithText("playground").performTouchInput { longClick() }

        composeTestRule.runOnIdle {
            assertEquals("/playground", removed)
            assertNull(selected)
            assertNull(reordered)
        }
    }

    @Test
    fun longPressAndDragReordersWithoutOfferingRemove() {
        setPicker()

        composeTestRule.onNodeWithText("playground").performTouchInput {
            down(center)
            // Past the long-press timeout, then a pause long enough that a
            // hold-based remove offer would have fired before the drag starts.
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            advanceEventTime(400)
            moveBy(Offset(0f, height.toFloat()))
            advanceEventTime(32)
            up()
        }

        composeTestRule.runOnIdle {
            assertEquals(listOf("/website", "/playground"), reordered)
            assertNull(removed)
            assertNull(selected)
        }
    }

    @Test
    fun longPressAndDragBackToStartCancelsInsteadOfRemoving() {
        setPicker()

        composeTestRule.onNodeWithText("playground").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, height.toFloat()))
            advanceEventTime(32)
            moveBy(Offset(0f, -height.toFloat()))
            advanceEventTime(32)
            up()
        }

        composeTestRule.runOnIdle {
            assertNull(removed)
            assertNull(reordered)
            assertNull(selected)
        }
    }
}
