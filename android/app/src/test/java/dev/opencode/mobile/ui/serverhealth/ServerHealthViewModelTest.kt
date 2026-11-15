package dev.opencode.mobile.ui.serverhealth

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileCatalog
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.serverhealth.InMemoryServerHealthStore
import dev.opencode.mobile.domain.serverhealth.ServerHealthClock
import dev.opencode.mobile.domain.serverhealth.ServerHealthConnectionState
import dev.opencode.mobile.domain.serverhealth.ServerHealthRange
import dev.opencode.mobile.domain.text.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerHealthViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(
        store: InMemoryServerHealthStore,
        harness: MonitorHarness,
        activeId: String? = "a",
        profileStore: ConnectionProfileStore = ProfilesStore(listOf(workProfile, homeProfile), activeId),
    ) = ServerHealthViewModel(
        monitor = harness.monitor,
        store = store,
        profileStore = profileStore,
        clock = ServerHealthClock { HEALTH_EPOCH + testScheduler.currentTime },
    ).also { it.workDispatcher = testDispatcher }

    @Test
    fun bind_showsTheProfileWithoutReadingPasswords() =
        runTest(testDispatcher) {
            val store = InMemoryServerHealthStore()
            val harness = MonitorHarness(backgroundScope, store) { HEALTH_EPOCH + testScheduler.currentTime }
            val profiles =
                object : ConnectionProfileStore by ProfilesStore(listOf(workProfile, homeProfile), "a") {
                    override suspend fun activeProfile(): ConnectionProfile = error("decrypts a password")

                    override suspend fun listProfiles(): List<ConnectionProfile> = error("decrypts every password")

                    override suspend fun catalogWithoutPasswords() =
                        ConnectionProfileCatalog(listOf(workProfile, homeProfile), activeId = "a")
                }
            val viewModel = viewModel(store, harness, profileStore = profiles)

            viewModel.bind("a")
            advanceUntilIdle()

            assertEquals("Work", viewModel.uiState.value.profileName)
            assertTrue(viewModel.uiState.value.isActiveProfile)
        }

    @Test
    fun bind_loadsTheProfileAndTheDayReport() =
        runTest(testDispatcher) {
            val store = InMemoryServerHealthStore()
            seedHealthHistory(store, "a")
            val harness = MonitorHarness(backgroundScope, store) { HEALTH_EPOCH + testScheduler.currentTime }
            val viewModel = viewModel(store, harness)

            viewModel.bind("a")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("Work", state.profileName)
            assertEquals(UiText.resource(R.string.server_health_protocol_v2), state.protocolLabel)
            assertEquals(ServerHealthRange.Day, state.range)
            assertEquals(ServerHealthRange.Day, state.report?.range)
            assertEquals(96, state.report?.buckets?.size)
            assertEquals(1, state.report?.drops)
            assertEquals(80.0, state.report?.uptimePercent!!, 0.01)
            assertEquals(listOf(12, null, 15), state.recentPings)
            assertEquals(15, state.recentP95Ms)
            assertEquals("1.4.0", state.serverVersion)
            assertTrue(state.isActiveProfile)
            assertEquals(ServerHealthConnectionState.Stopped, state.status)
            assertNull(state.currentPingMs)
        }

    @Test
    fun selectRange_rebuildsTheReportForThatRange() =
        runTest(testDispatcher) {
            val store = InMemoryServerHealthStore()
            seedHealthHistory(store, "a")
            val harness = MonitorHarness(backgroundScope, store) { HEALTH_EPOCH + testScheduler.currentTime }
            val viewModel = viewModel(store, harness)
            viewModel.bind("a")
            advanceUntilIdle()

            viewModel.selectRange(ServerHealthRange.Hour)
            advanceUntilIdle()

            assertEquals(ServerHealthRange.Hour, viewModel.uiState.value.range)
            assertEquals(
                ServerHealthRange.Hour,
                viewModel.uiState.value.report
                    ?.range,
            )
            assertEquals(
                60,
                viewModel.uiState.value.report
                    ?.buckets
                    ?.size,
            )

            viewModel.selectRange(ServerHealthRange.Week)
            advanceUntilIdle()

            assertEquals(
                84,
                viewModel.uiState.value.report
                    ?.buckets
                    ?.size,
            )
        }

    @Test
    fun liveStatus_showsOnlyForTheActiveProfile() =
        runTest(testDispatcher) {
            val store = InMemoryServerHealthStore()
            val harness = MonitorHarness(backgroundScope, store) { HEALTH_EPOCH + testScheduler.currentTime }
            harness.monitor.start()
            harness.connect("a")
            val active = viewModel(store, harness)
            val other = viewModel(store, harness)

            active.bind("a")
            other.bind("b")
            runCurrent()
            advanceUntilIdle()

            assertEquals(ServerHealthConnectionState.Live, active.uiState.value.status)
            assertEquals(2, active.uiState.value.liveTargets)
            assertEquals(2, active.uiState.value.requiredTargets)
            assertEquals(ServerHealthConnectionState.Stopped, other.uiState.value.status)
            assertFalse(other.uiState.value.isActiveProfile)
            assertEquals(0, other.uiState.value.requiredTargets)
            assertTrue(
                other.uiState.value.report
                    ?.isEmpty == true,
            )
        }
}
