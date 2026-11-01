package dev.opencode.mobile.ui.sessionchat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineView
import dev.opencode.mobile.ui.sessionchat.view.SessionChatTimelineViewProps
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionChatTimelineActivitySlotTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun activityPillsStayReachableWhileTheTimelineIsNotReady() {
        var timeline by mutableStateOf(SessionChatTimelineState.Loading)
        val clicked = mutableListOf<SessionChatActivityItem>()
        composeTestRule.setContent {
            OpenCodeTheme {
                SessionChatTimelineView(
                    props =
                        SessionChatTimelineViewProps(
                            timeline = timeline,
                            messages = emptyList(),
                            isRefreshing = false,
                            errorMessage = "Timeline failed",
                            activityItems =
                                listOf(SessionChatActivityItem.Queued(2), SessionChatActivityItem.ReviewPermission),
                            onActivityClick = { clicked += it },
                            onRefresh = {},
                            onMessageActionsTap = {},
                        ),
                )
            }
        }

        listOf(
            SessionChatTimelineState.Loading,
            SessionChatTimelineState.Creating,
            SessionChatTimelineState.Empty,
            SessionChatTimelineState.Error,
        ).forEach { state ->
            composeTestRule.runOnIdle { timeline = state }
            // Needs input wins the slot; the queued prompts sit behind "+1".
            composeTestRule.onNodeWithContentDescription("Review permission request").assertIsDisplayed().performClick()
            composeTestRule.onNodeWithContentDescription("Show 1 more").assertIsDisplayed()
        }

        composeTestRule.runOnIdle { assertEquals(List(4) { SessionChatActivityItem.ReviewPermission }, clicked) }
    }
}
