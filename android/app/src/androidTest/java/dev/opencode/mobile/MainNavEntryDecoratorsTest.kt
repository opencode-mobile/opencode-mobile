package dev.opencode.mobile

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Screen ViewModels are keyed per route (`hiltViewModel(key = …)`). Without the
 * ViewModelStore entry decorator they attach to the Activity store and never clear,
 * so one ViewModel accumulates per session ever opened. This pins the root
 * [NavDisplay] wiring in [rememberMainNavEntryDecorators].
 */
class MainNavEntryDecoratorsTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Serializable private data object RootRoute : NavKey

    @Serializable private data class DetailRoute(
        val id: String,
    ) : NavKey

    class ClearedProbeViewModel : ViewModel() {
        var onClearedListener: () -> Unit = {}

        override fun onCleared() {
            onClearedListener()
            super.onCleared()
        }
    }

    @Test
    fun keyedEntryViewModel_isClearedWhenEntryIsPopped() {
        val clearedCount = AtomicInteger(0)
        lateinit var backStack: NavBackStack<NavKey>
        composeTestRule.setContent {
            backStack = rememberNavBackStack(RootRoute)
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = rememberMainNavEntryDecorators(),
                entryProvider =
                    entryProvider {
                        entry<RootRoute> { Text("root") }
                        entry<DetailRoute> { key ->
                            val probe: ClearedProbeViewModel = viewModel(key = "detail-${key.id}")
                            probe.onClearedListener = { clearedCount.incrementAndGet() }
                            Text("detail ${key.id}")
                        }
                    },
            )
        }

        composeTestRule.runOnIdle { backStack.add(DetailRoute("1")) }
        composeTestRule.onNodeWithText("detail 1").assertExists()
        composeTestRule.runOnIdle { backStack.removeLastOrNull() }
        composeTestRule.onNodeWithText("root").assertExists()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { clearedCount.get() == 1 }
    }
}
