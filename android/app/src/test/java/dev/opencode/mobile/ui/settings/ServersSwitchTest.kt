package dev.opencode.mobile.ui.settings

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.testConnectionSessionCoordinator
import dev.opencode.mobile.domain.serverhealth.InMemoryServerHealthStore
import dev.opencode.mobile.domain.serverhealth.ServerHealthClock
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import dev.opencode.mobile.ui.playground.PlaygroundCredentialStore
import dev.opencode.mobile.ui.playground.PlaygroundDraftStore
import dev.opencode.mobile.ui.playground.PlaygroundModelSelectionStore
import dev.opencode.mobile.ui.serverhealth.HEALTH_EPOCH
import dev.opencode.mobile.ui.serverhealth.MonitorHarness
import dev.opencode.mobile.ui.serverhealth.ProfilesStore
import dev.opencode.mobile.ui.serverhealth.homeProfile
import dev.opencode.mobile.ui.serverhealth.workProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServersSwitchTest {
    private val testDispatcher = StandardTestDispatcher()
    private val buildProfile =
        ConnectionProfile(baseUrl = "https://build.example.com", username = "opencode", id = "c", name = "Build")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Counts the profile switches that reach the store. */
    private class CountingStore(
        private val inner: ProfilesStore,
    ) : ConnectionProfileStore by inner {
        val applied = mutableListOf<String>()

        override suspend fun applyProfile(id: String) {
            applied += id
            inner.applyProfile(id)
        }
    }

    private suspend fun TestScope.serversViewModel(store: CountingStore): ServersViewModel {
        val json = Json { ignoreUnknownKeys = true }
        val activeConnectionManager = ActiveConnectionManager(store)
        activeConnectionManager.reloadFromStore()
        val healthStore = InMemoryServerHealthStore()
        val coordinator =
            testConnectionSessionCoordinator(
                activeConnectionManager = activeConnectionManager,
                connectionProfileStore = store,
                secureCredentialStore = PlaygroundCredentialStore,
                sessionModelSelectionStore = PlaygroundModelSelectionStore(),
                composerDraftStore = PlaygroundDraftStore(),
                httpClient = OpencodeHttpClient(json, activeConnectionManager),
                sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                serverHealthStore = healthStore,
            )
        val harness = MonitorHarness(backgroundScope, healthStore) { HEALTH_EPOCH + testScheduler.currentTime }
        return ServersViewModel(
            connectionSessionCoordinator = coordinator,
            serverHealthMonitor = harness.monitor,
            serverHealthStore = healthStore,
            clock = ServerHealthClock { HEALTH_EPOCH + testScheduler.currentTime },
        ).also { it.workDispatcher = testDispatcher }
    }

    private fun store() = CountingStore(ProfilesStore(listOf(workProfile, homeProfile, buildProfile), activeId = "a"))

    @Test
    fun switchTo_makesTheTappedServerActive() =
        runTest(testDispatcher) {
            val store = store()
            val viewModel = serversViewModel(store)
            advanceUntilIdle()

            viewModel.switchTo("b")
            advanceUntilIdle()

            assertEquals(listOf("b"), store.applied)
            assertEquals("b", viewModel.uiState.value.activeId)
        }

    @Test
    fun switchTo_theActiveServer_doesNothing() =
        runTest(testDispatcher) {
            val store = store()
            val viewModel = serversViewModel(store)
            advanceUntilIdle()

            viewModel.switchTo("a")
            advanceUntilIdle()

            assertEquals(emptyList<String>(), store.applied)
            assertEquals("a", viewModel.uiState.value.activeId)
        }

    @Test
    fun switchTo_anUnknownServer_doesNothing() =
        runTest(testDispatcher) {
            val store = store()
            val viewModel = serversViewModel(store)
            advanceUntilIdle()

            viewModel.switchTo("missing")
            advanceUntilIdle()

            assertEquals(emptyList<String>(), store.applied)
            assertEquals("a", viewModel.uiState.value.activeId)
        }

    @Test
    fun switchTo_whileASwitchRuns_ignoresTheSecondTap() =
        runTest(testDispatcher) {
            val store = store()
            val viewModel = serversViewModel(store)
            advanceUntilIdle()

            viewModel.switchTo("b")
            viewModel.switchTo("c")
            advanceUntilIdle()

            assertEquals(listOf("b"), store.applied)
            assertEquals("b", viewModel.uiState.value.activeId)
        }
}
