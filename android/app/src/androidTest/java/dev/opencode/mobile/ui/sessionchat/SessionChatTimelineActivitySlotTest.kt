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
            SessionChatTimelineState.Draft,
            SessionChatTimelineState.Empty,
            SessionChatTimelineState.Error,
        ).forEach { state ->
            composeTestRule.runOnIdle { timeline = state }
            // The request is on the turn pill; the queued prompts stay a secondary pill beside it.
            composeTestRule.onNodeWithContentDescription("Review permission request").assertIsDisplayed().performClick()
            composeTestRule.onNodeWithContentDescription("2 queued prompts. Open Up next").assertIsDisplayed()
        }

        composeTestRule.runOnIdle { assertEquals(List(4) { SessionChatActivityItem.ReviewPermission }, clicked) }
    }
}
