package dev.opencode.mobile.domain.connection

import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.composer.InMemoryComposerDraftStore
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.recovery.RecordingForegroundEventStream
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSessionCoordinatorTest {
    @Test
    fun clearConnectionData_clearsModelSelectionCache() =
        runBlocking {
            val store = FakeSessionModelSelectionStore()
            val draftStore = InMemoryComposerDraftStore()
            store.seed(
                serverBaseUrl = "http://127.0.0.1:4096/",
                worktree = "/repo",
                sessionId = "ses-1",
                selection = SessionModelSelection(providerId = "openai", modelId = "gpt-4"),
            )
            val json = Json { ignoreUnknownKeys = true }
            val connectionProfileStore = FakeConnectionProfileStore()
            val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
            activeConnectionManager.applyProfile(
                ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = ""),
            )
            val coordinator =
                ConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = store,
                    composerDraftStore = draftStore,
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                    eventStream = FakeForegroundEventStream(),
                )

            draftStore.save(
                ModelSelectionConnectionIdentity("http://127.0.0.1:4096/", "opencode"),
                "/repo",
                "ses-1",
                ComposerDraft(text = "draft"),
            )

            coordinator.clearConnectionData()

            assertNull(
                store.load(
                    serverBaseUrl = "http://127.0.0.1:4096/",
                    worktree = "/repo",
                    sessionId = "ses-1",
                ),
            )
            assertNull(
                draftStore.load(
                    ModelSelectionConnectionIdentity("http://127.0.0.1:4096/", "opencode"),
                    "/repo",
                    "ses-1",
                ),
            )
        }

    @Test
    fun saveProfile_doesNotEmitWhenEditingInactive() =
        runTest {
            val connectionProfileStore = CatalogConnectionProfileStore()
            val json = Json { ignoreUnknownKeys = true }
            val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
            val coordinator =
                ConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(),
                    composerDraftStore = InMemoryComposerDraftStore(),
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                    eventStream = FakeForegroundEventStream(),
                )
            val events = mutableListOf<Unit>()
            backgroundScope.launch { coordinator.profileSaved.collect { events += it } }
            advanceUntilIdle()

            coordinator.saveProfile(
                ConnectionProfile(
                    baseUrl = "http://10.0.0.1:4096/",
                    username = "opencode",
                    password = "secret",
                    name = "Home",
                ),
            )
            coordinator.saveProfile(
                ConnectionProfile(
                    baseUrl = "http://10.0.0.2:4096/",
                    username = "opencode",
                    password = "secret",
                    name = "Work",
                ),
            )
            val home = connectionProfileStore.listProfiles().first { it.name == "Home" }
            events.clear()

            coordinator.saveProfile(home.copy(name = "Studio"))
            advanceUntilIdle()

            assertTrue(events.isEmpty())
            assertEquals("Work", connectionProfileStore.activeProfile()?.name)
            assertEquals("Studio", connectionProfileStore.listProfiles().first { it.id == home.id }.name)
        }

    @Test
    fun switchProfile_stopsStreamBeforeRebuildingClients() =
        runTest {
            val connectionProfileStore = CatalogConnectionProfileStore()
            val json = Json { ignoreUnknownKeys = true }
            val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
            val stream = RecordingForegroundEventStream()
            val coordinator =
                ConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(),
                    composerDraftStore = InMemoryComposerDraftStore(),
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                    eventStream = stream,
                )
            coordinator.saveProfile(
                ConnectionProfile(
                    baseUrl = "http://10.0.0.1:4096/",
                    username = "opencode",
                    password = "secret",
                    name = "Home",
                ),
            )
            coordinator.saveProfile(
                ConnectionProfile(
                    baseUrl = "http://10.0.0.2:4096/",
                    username = "opencode",
                    password = "secret",
                    name = "Work",
                ),
            )
            val home = connectionProfileStore.listProfiles().first { it.name == "Home" }
            stream.start()
            stream.ops.clear()
            coordinator.switchProfile(home.id)
            advanceUntilIdle()
            assertEquals(listOf("stop"), stream.ops.toList())
            assertEquals("Home", connectionProfileStore.activeProfile()?.name)
        }
}

private class FakeSessionModelSelectionStore : SessionModelSelectionStore {
    private val saved = mutableMapOf<String, SessionModelSelection>()

    fun seed(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) {
        saved[key(serverBaseUrl, worktree, sessionId)] = selection
    }

    suspend fun load(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection? = saved[key(serverBaseUrl, worktree, sessionId)]

    suspend fun save(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) {
        saved[key(serverBaseUrl, worktree, sessionId)] = selection
    }

    override suspend fun loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection? = load(connectionIdentity.serverBaseUrl, worktree, sessionId)

    override suspend fun saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) = save(connectionIdentity.serverBaseUrl, worktree, sessionId, selection)

    override suspend fun deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ) {
        saved.remove(key(connectionIdentity.serverBaseUrl, worktree, sessionId))
    }

    override suspend fun deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionId: String,
    ) {
        saved.keys.removeAll { it.startsWith(connectionIdentity.serverBaseUrl) && it.endsWith("|$sessionId") }
    }

    override suspend fun loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
    ): SessionModelSelection? = null

    override suspend fun saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection,
    ) = Unit

    override suspend fun clearAll() {
        saved.clear()
    }

    private fun key(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
    ): String = "$serverBaseUrl|$worktree|$sessionId"
}

private class CatalogConnectionProfileStore : ConnectionProfileStore {
    private var catalog = ConnectionProfileCatalog()

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

private class FakeConnectionProfileStore : ConnectionProfileStore {
    override suspend fun activeProfile(): ConnectionProfile? = null

    override suspend fun saveProfile(profile: ConnectionProfile) = Unit

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

    override suspend fun clearAll() = Unit
}

private class FakeSecureCredentialStore : SecureCredentialStore {
    override fun readPassword(serverBaseUrl: String): String? = null

    override fun writePassword(
        serverBaseUrl: String,
        password: String,
    ) = Unit

    override fun deletePassword(serverBaseUrl: String) = Unit

    override fun clearAll() = Unit
}
