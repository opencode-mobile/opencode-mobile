package dev.opencode.mobile

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.Collections

/**
 * The Home split view renders Session Chat and the screens it opens in a nested [NavDisplay]
 * inside the root Home entry. This pins that system back pops the pane stack before clearing
 * the selection, and that replacing the pane root clears the old entries' keyed ViewModels.
 */
class HomeSessionPaneNavigationTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    class ClearedProbeViewModel : ViewModel() {
        var onClearedListener: () -> Unit = {}

        override fun onCleared() {
            onClearedListener()
            super.onCleared()
        }
    }

    private val sessionA = SessionChat(sessionId = "a", sessionTitle = "A", worktree = "/repo")
    private val sessionB = SessionChat(sessionId = "b", sessionTitle = "B", worktree = "/repo")
    private val contextA = SessionContext(sessionId = "a", worktree = "/repo")

    private val clearedProbes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var rootStack: NavBackStack<NavKey>
    private lateinit var paneStack: NavBackStack<NavKey>

    @Composable
    private fun Probe(label: String) {
        val probe: ClearedProbeViewModel = viewModel(key = "probe-$label")
        probe.onClearedListener = { clearedProbes.add(label) }
        Text(label)
    }

    private fun setPaneContent() {
        composeTestRule.setContent {
            rootStack = rememberNavBackStack(ProjectListHome)
            NavDisplay(
                backStack = rootStack,
                onBack = { rootStack.popRouteUnlessRoot() },
                entryDecorators = rememberMainNavEntryDecorators(),
                entryProvider =
                    entryProvider {
                        entry<ProjectListHome> {
                            paneStack = rememberNavBackStack(HomeSessionPanePlaceholder)
                            BackHandler(enabled = paneStack.paneSelection != null && paneStack.size == 1) {
                                paneStack.resetPane()
                            }
                            NavDisplay(
                                backStack = paneStack,
                                onBack = { paneStack.popRouteUnlessRoot() },
                                entryDecorators = rememberMainNavEntryDecorators(),
                                entryProvider =
                                    entryProvider {
                                        entry<HomeSessionPanePlaceholder> { Text("placeholder") }
                                        entry<SessionChat> { key -> Probe("chat ${key.sessionId}") }
                                        entry<SessionContext> { key -> Probe("context ${key.sessionId}") }
                                    },
                            )
                        }
                    },
            )
        }
    }

    private fun pressBack() {
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun systemBack_popsThePaneStackBeforeClearingTheSelection() {
        setPaneContent()
        composeTestRule.runOnIdle {
            paneStack.selectPaneSession(sessionA)
            paneStack.add(contextA)
        }
        composeTestRule.onNodeWithText("context a").assertExists()

        pressBack()

        composeTestRule.onNodeWithText("chat a").assertExists()
        composeTestRule.runOnIdle {
            assertEquals(listOf<NavKey>(sessionA), paneStack.toList())
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { clearedProbes.contains("context a") }

        pressBack()

        composeTestRule.onNodeWithText("placeholder").assertExists()
        composeTestRule.runOnIdle {
            assertEquals(listOf<NavKey>(ProjectListHome), rootStack.toList())
            assertFalse(composeTestRule.activity.isFinishing)
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { clearedProbes.contains("chat a") }
    }

    @Test
    fun selectingAnotherSession_clearsTheReplacedEntriesViewModels() {
        setPaneContent()
        composeTestRule.runOnIdle {
            paneStack.selectPaneSession(sessionA)
            paneStack.add(contextA)
        }
        composeTestRule.onNodeWithText("context a").assertExists()

        composeTestRule.runOnIdle { paneStack.selectPaneSession(sessionB) }

        composeTestRule.onNodeWithText("chat b").assertExists()
        composeTestRule.runOnIdle {
            assertEquals(listOf<NavKey>(sessionB), paneStack.toList())
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            clearedProbes.containsAll(listOf("chat a", "context a"))
        }
    }
}
