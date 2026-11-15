package dev.opencode.mobile.ui.serverhealth

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.testConnectionSessionCoordinator
import dev.opencode.mobile.domain.serverhealth.InMemoryServerHealthStore
import dev.opencode.mobile.domain.serverhealth.ServerHealthClock
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.text.resolve
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import dev.opencode.mobile.ui.playground.PlaygroundCredentialStore
import dev.opencode.mobile.ui.playground.PlaygroundDraftStore
import dev.opencode.mobile.ui.playground.PlaygroundModelSelectionStore
import dev.opencode.mobile.ui.settings.ServersViewModel
import dev.opencode.mobile.ui.text.EnglishStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServersHealthQuickViewTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.serversViewModel(
        store: InMemoryServerHealthStore,
        harness: MonitorHarness,
    ): ServersViewModel {
        val json = Json { ignoreUnknownKeys = true }
        val profiles = ProfilesStore(listOf(workProfile, homeProfile), activeId = "a")
        val activeConnectionManager = ActiveConnectionManager(profiles)
        activeConnectionManager.reloadFromStore()
        val coordinator =
            testConnectionSessionCoordinator(
                activeConnectionManager = activeConnectionManager,
                connectionProfileStore = profiles,
                secureCredentialStore = PlaygroundCredentialStore,
                sessionModelSelectionStore = PlaygroundModelSelectionStore(),
                composerDraftStore = PlaygroundDraftStore(),
                httpClient = OpencodeHttpClient(json, activeConnectionManager),
                sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                serverHealthStore = store,
            )
        return ServersViewModel(
            connectionSessionCoordinator = coordinator,
            serverHealthMonitor = harness.monitor,
            serverHealthStore = store,
            clock = ServerHealthClock { HEALTH_EPOCH + testScheduler.currentTime },
        ).also { it.workDispatcher = testDispatcher }
    }

    @Test
    fun quickView_summarizesTheActiveProfilesDay() =
        runTest(testDispatcher) {
            val store = InMemoryServerHealthStore()
            seedHealthHistory(store, "a")
            val harness = MonitorHarness(backgroundScope, store) { HEALTH_EPOCH + testScheduler.currentTime }
            val viewModel = serversViewModel(store, harness)
            advanceUntilIdle()

            val quickView = viewModel.uiState.value.healthQuickView!!
            assertEquals("a", viewModel.uiState.value.activeId)
            assertEquals(ServerHealthConnectionState.Stopped, quickView.state)
            assertNull(quickView.currentPingMs)
            assertEquals(listOf(12, null, 15), quickView.recentPings)
            assertEquals(80.0, quickView.uptimePercent!!, 0.01)
            assertEquals(1, quickView.reconnects)
            assertEquals("Uptime 80.0% · 1 reconnect today", quickView.caption.resolve(EnglishStrings))
        }

    @Test
    fun quickView_followsTheLiveConnection() =
        runTest(testDispatcher) {
            val store = InMemoryServerHealthStore()
            val harness = MonitorHarness(backgroundScope, store) { HEALTH_EPOCH + testScheduler.currentTime }
            harness.monitor.start()
            val viewModel = serversViewModel(store, harness)
            advanceUntilIdle()

            assertEquals(
                UiText.resource(R.string.server_health_quick_view_measuring),
                viewModel.uiState.value.healthQuickView
                    ?.caption,
            )

            harness.connect("a")
            runCurrent()
            advanceUntilIdle()

            assertEquals(
                ServerHealthConnectionState.Live,
                viewModel.uiState.value.healthQuickView
                    ?.state,
            )
        }

    @Test
    fun openHealth_requestsTheServerHealthPage() =
        runTest(testDispatcher) {
            val store = InMemoryServerHealthStore()
            val harness = MonitorHarness(backgroundScope, store) { HEALTH_EPOCH + testScheduler.currentTime }
            val viewModel = serversViewModel(store, harness)
            advanceUntilIdle()
            val request = async { viewModel.openHealthRequests.first() }
            advanceUntilIdle()

            viewModel.openHealth("a")
            advanceUntilIdle()

            assertEquals("a", request.await())
        }
}
