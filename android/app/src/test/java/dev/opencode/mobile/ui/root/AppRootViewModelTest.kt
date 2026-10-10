package dev.opencode.mobile.ui.root

import dev.opencode.mobile.domain.composer.InMemoryComposerDraftStore
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileCatalog
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.connection.testConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.StreamOccupancy
import dev.opencode.mobile.domain.event.WorldSignal
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.preferences.AppOnboardingPreferences
import dev.opencode.mobile.domain.recovery.RecordingForegroundEventStream
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppRootViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        AppOnboardingPreferences.markCompleted()
    }

    @After
    fun tearDown() {
        AppOnboardingPreferences.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun load_startsSseAfterHomeWithoutRebuildingClientsAgain() =
        runTest(dispatcher) {
            val store = SeededProfileStore()
            val json = Json { ignoreUnknownKeys = true }
            val manager = ActiveConnectionManager(store)
            val stream = RecordingForegroundEventStream()
            val world = RecordingWorldSignals()
            val httpClient = OpencodeHttpClient(json, manager)
            val sseHttpClient = OpencodeSseHttpClient(json, manager)
            val coordinator =
                testConnectionSessionCoordinator(
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                    secureCredentialStore = EmptySecureCredentialStore(),
                    sessionModelSelectionStore = EmptyModelSelectionStore(),
                    composerDraftStore = InMemoryComposerDraftStore(),
                    httpClient = httpClient,
                    sseHttpClient = sseHttpClient,
                    eventStream = stream,
                )
            val viewModel =
                AppRootViewModel(
                    connectionProfileStore = store,
                    activeConnectionManager = manager,
                    connectionSessionCoordinator = coordinator,
                    worldSignals = world,
                    eventStream = stream,
                    httpClient = httpClient,
                    sseHttpClient = sseHttpClient,
                )
            advanceUntilIdle()

            val rest = httpClient.get()
            val sse = sseHttpClient.get()
            assertEquals(AppRootState.Home, viewModel.state.value)
            assertEquals(listOf("connection-available"), world.ops)
            viewModel.completeAppOnboarding(openConnectionSetup = false)
            assertSame(rest, httpClient.get())
            assertSame(sse, sseHttpClient.get())
        }

    @Test
    fun clearConnectionData_bumpsHomeResetGeneration() =
        runTest(dispatcher) {
            val store = SeededProfileStore()
            val json = Json { ignoreUnknownKeys = true }
            val manager = ActiveConnectionManager(store)
            val stream = RecordingForegroundEventStream()
            val httpClient = OpencodeHttpClient(json, manager)
            val sseHttpClient = OpencodeSseHttpClient(json, manager)
            val coordinator =
                testConnectionSessionCoordinator(
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                    secureCredentialStore = EmptySecureCredentialStore(),
                    sessionModelSelectionStore = EmptyModelSelectionStore(),
                    composerDraftStore = InMemoryComposerDraftStore(),
                    httpClient = httpClient,
                    sseHttpClient = sseHttpClient,
                    eventStream = stream,
                )
            val viewModel =
                AppRootViewModel(
                    connectionProfileStore = store,
                    activeConnectionManager = manager,
                    connectionSessionCoordinator = coordinator,
                    worldSignals = RecordingWorldSignals(),
                    eventStream = stream,
                    httpClient = httpClient,
                    sseHttpClient = sseHttpClient,
                )
            advanceUntilIdle()
            val before = viewModel.homeResetGeneration.value

            coordinator.clearConnectionData()
            advanceUntilIdle()

            assertEquals(before + 1, viewModel.homeResetGeneration.value)
        }
}

private class RecordingWorldSignals : WorldSignals {
    val ops = mutableListOf<String>()
    private val _signals = MutableSharedFlow<WorldSignal>(extraBufferCapacity = 8)
    override val signals: SharedFlow<WorldSignal> = _signals.asSharedFlow()
    private val _occupancy = MutableStateFlow(StreamOccupancy.Active)
    override val occupancy: StateFlow<StreamOccupancy> = _occupancy.asStateFlow()

    override fun onConnectionAvailable() {
        ops += "connection-available"
    }
}

private class SeededProfileStore : ConnectionProfileStore {
    private var catalog =
        ConnectionProfileCatalog().save(
            ConnectionProfile(
                baseUrl = "http://127.0.0.1:4096/",
                username = "opencode",
                password = "secret",
                name = "Local",
            ),
        )

    override suspend fun activeProfile(): ConnectionProfile? = catalog.activeProfile()

    override suspend fun listProfiles(): List<ConnectionProfile> = catalog.profiles

    override suspend fun saveProfile(profile: ConnectionProfile) {
        catalog = catalog.save(profile)
    }

    override suspend fun applyProfile(id: String) {
        catalog = catalog.apply(id)
    }

    override suspend fun removeProfile(id: String) {
        catalog = catalog.remove(id)
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) = Unit

    override suspend fun clearAll() {
        catalog = ConnectionProfileCatalog()
    }
}

private class EmptySecureCredentialStore : SecureCredentialStore {
    override fun readPassword(serverBaseUrl: String): String? = null

    override fun writePassword(
        serverBaseUrl: String,
        password: String,
    ) = Unit

    override fun deletePassword(serverBaseUrl: String) = Unit

    override fun clearAll() = Unit
}

private class EmptyModelSelectionStore : SessionModelSelectionStore {
    override suspend fun loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection? = null

    override suspend fun saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) = Unit

    override suspend fun deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ) = Unit

    override suspend fun deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionId: String,
    ) = Unit

    override suspend fun loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
    ): SessionModelSelection? = null

    override suspend fun saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection,
    ) = Unit

    override suspend fun clearAll() = Unit
}
