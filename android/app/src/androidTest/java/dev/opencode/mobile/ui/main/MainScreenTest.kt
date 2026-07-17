package dev.opencode.mobile.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [dev.opencode.mobile.ui.main.MainScreen]. */
class MainScreenTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setup() {
        composeTestRule.setContent {
            MainScreen(
                uiState = MainScreenUiState.Disconnected,
                liveUpdatesConnectionState = EventStreamConnectionState.Idle,
                onSettingsClick = {},
                onSessionClick = {},
                onEditConnection = {},
                onConnectClick = {},
                onRetryProbe = {},
                onRefresh = {},
                onAddProjectClick = {},
                onFocusProject = {},
                onDismissAddProject = {},
                onNavigateAddProjectUp = {},
                onNavigateAddProjectInto = {},
                onRetryAddProjectNavigator = {},
                onSelectCurrentDirectory = {},
                onSeeAllSessions = {},
                onDismissSeeAll = {},
                onNewSession = {},
                onConsumePendingOpenSession = {},
            )
        }
    }

    @Test
    fun firstItem_exists() {
        composeTestRule.onNodeWithText("Connect to your server").assertExists()
    }
}
