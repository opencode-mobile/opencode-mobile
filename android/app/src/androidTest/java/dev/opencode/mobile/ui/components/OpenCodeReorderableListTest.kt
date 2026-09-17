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
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.up
import dev.opencode.mobile.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Select, reorder and remove each own a distinct gesture on a picker row, and
 * the point of the split is that none of them can consume another's input:
 * vertical is order, horizontal is remove, a plain release is select.
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
                // The bottom sheet wraps the rows in a no-op clickable that
                // consumes the same pointer, so it belongs in the repro.
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
    fun longPressAndDragReorders() {
        setPicker()

        composeTestRule.onNodeWithText("playground").performTouchInput {
            down(center)
            // Past the long-press timeout, then a pause long enough that any
            // hold-based remove would have fired before the drag starts.
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
    fun longPressAndReleaseNeverRemoves() {
        setPicker()

        composeTestRule.onNodeWithText("playground").performTouchInput { longClick() }

        composeTestRule.runOnIdle {
            assertNull(removed)
            assertNull(selected)
            assertNull(reordered)
        }
    }

    @Test
    fun swipeRevealsRemoveAndTappingItRemovesTheProject() {
        setPicker()

        composeTestRule.onNodeWithText("playground").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertNull(removed) // revealing alone must not remove
            assertNull(selected)
        }

        composeTestRule.onNodeWithText("Remove").performTouchInput { click() }

        composeTestRule.runOnIdle {
            assertEquals("/playground", removed)
            assertNull(reordered)
            assertNull(selected)
        }
    }

    @Test
    fun shortSwipeSettlesClosedWithoutSelecting() {
        setPicker()

        composeTestRule.onNodeWithText("playground").performTouchInput {
            down(center)
            // Past touch slop, so this commits to the swipe axis rather than
            // staying a tap, but far short of the open threshold.
            moveBy(Offset(-(viewConfiguration.touchSlop + 8f), 0f))
            advanceEventTime(16)
            moveBy(Offset(-12f, 0f))
            advanceEventTime(16)
            up()
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertNull(removed)
            assertNull(selected) // a committed swipe is not a tap
            assertNull(reordered)
        }
        composeTestRule.onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test
    fun allProjectsRowNeverReveals() {
        setPicker()

        composeTestRule.onNodeWithText("All projects").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { assertNull(removed) }
        composeTestRule.onNodeWithText("Remove").assertDoesNotExist()
    }
}
