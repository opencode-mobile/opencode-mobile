package dev.opencode.mobile.ui.sessionchat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineView
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineViewProps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The bottom-left activity pill reserves one pill row in the timeline. While the list is pinned
 * to the end that reserve springs, so the rows glide; away from the end nothing moves.
 */
class SessionChatPillReserveGlideTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var activity by mutableStateOf<List<SessionChatActivityItem>>(emptyList())

    private fun setTimeline() {
        val messages =
            (1..40).map { i ->
                SessionChatMessageItem(
                    id = "m$i",
                    role = if (i % 2 == 1) MessageRole.User else MessageRole.Assistant,
                    text = "Row $i",
                    metadataLabel = "",
                )
            }
        composeTestRule.setContent {
            OpenCodeTheme {
                Box(Modifier.fillMaxSize()) {
                    SessionChatTimelineView(
                        props =
                            SessionChatTimelineViewProps(
                                timeline = SessionChatTimelineState.Ready,
                                messages = messages,
                                isRefreshing = false,
                                errorMessage = null,
                                bottomOverlayInset = 100.dp,
                                activityItems = activity,
                                onRefresh = {},
                                onMessageActionsTap = {},
                            ),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun top(text: String): Float =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot().top.value

    private fun bottom(text: String): Float =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot().bottom.value

    @Test
    fun pinnedListGlidesByTheReserveWhenAPillAppears() {
        setTimeline()
        val before = bottom("Row 40")
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnUiThread { activity = listOf(SessionChatActivityItem.ReviewPermission) }

        val samples = mutableListOf(before)
        repeat(90) {
            composeTestRule.mainClock.advanceTimeByFrame()
            samples += bottom("Row 40")
        }
        println("GLIDE show: " + samples.joinToString(" ") { "%.2f".format(it) })

        val deltas = samples.zipWithNext { a, b -> a - b }
        val moved = before - samples.last()
        assertEquals("settles at the pill row reserve", 36f, moved, 0.6f)
        assertTrue("never moves down while the reserve grows: $deltas", deltas.all { it > -0.6f })
        assertTrue("no single frame carries the whole move: ${deltas.max()}", deltas.max() < 36f * 0.6f)
        assertTrue("spread over several frames", deltas.count { it > 0.1f } >= 5)

        // And back: the reserve shrinks with the same glide.
        val shown = samples.last()
        composeTestRule.runOnUiThread { activity = emptyList() }
        val back = mutableListOf(shown)
        repeat(90) {
            composeTestRule.mainClock.advanceTimeByFrame()
            back += bottom("Row 40")
        }
        println("GLIDE hide: " + back.joinToString(" ") { "%.2f".format(it) })
        val backDeltas = back.zipWithNext { a, b -> b - a }
        assertEquals("returns to the idle position", before, back.last(), 0.6f)
        assertTrue("no single frame carries the whole move: ${backDeltas.max()}", backDeltas.max() < 36f * 0.6f)
        assertTrue("spread over several frames", backDeltas.count { it > 0.1f } >= 5)
    }

    @Test
    fun scrolledAwayListDoesNotMoveWhenAPillAppears() {
        setTimeline()
        composeTestRule.onRoot().performTouchInput { repeat(3) { swipeDown() } }
        composeTestRule.waitForIdle()
        val visible = (1..40).first { runCatching { top("Row $it") }.isSuccess }
        val before = top("Row $visible")
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnUiThread { activity = listOf(SessionChatActivityItem.ReviewPermission) }
        repeat(60) {
            composeTestRule.mainClock.advanceTimeByFrame()
            assertEquals("Row $visible must not move", before, top("Row $visible"), 0.01f)
        }
    }
}
