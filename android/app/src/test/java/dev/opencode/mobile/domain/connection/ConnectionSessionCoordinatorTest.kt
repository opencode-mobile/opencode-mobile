package dev.opencode.mobile.domain.connection

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.opencode.mobile.data.local.DataStoreComposerDraftStore
import dev.opencode.mobile.data.local.DataStoreSessionModelSelectionStore
import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.composer.InMemoryComposerDraftStore
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.event.DefaultForegroundEventStream
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.FakeWorldSignals
import dev.opencode.mobile.domain.event.LiveConnection
import dev.opencode.mobile.domain.event.WorldSignal
import dev.opencode.mobile.domain.live.ProjectionCaches
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.recovery.RecordingForegroundEventStream
import dev.opencode.mobile.domain.sessioncache.DefaultSessionCache
import dev.opencode.mobile.domain.sessioncache.InMemorySessionCacheStore
import dev.opencode.mobile.domain.sessioncache.SessionCacheCommitMeta
import dev.opencode.mobile.domain.sessioncache.SessionCacheEvictSelector
import dev.opencode.mobile.domain.sessioncache.SessionCacheKey
import dev.opencode.mobile.domain.sessioncache.SessionCacheKind
import dev.opencode.mobile.domain.sessioncache.SessionCacheValue
import dev.opencode.mobile.domain.sync.NoOpSyncCoordinator
import dev.opencode.mobile.domain.sync.SyncCoordinator
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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
                testConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = store,
                    composerDraftStore = draftStore,
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
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
    fun removeProfile_purgesCacheDraftsAndModelSelectionsOfThatServerOnly() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val modelStore = DataStoreSessionModelSelectionStore(preferencesDataStore(backgroundScope, "model"), json)
            val draftStore = DataStoreComposerDraftStore(preferencesDataStore(backgroundScope, "draft"), json)
            val cacheStore = InMemorySessionCacheStore()
            val connectionProfileStore = CatalogConnectionProfileStore()
            val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
            val cache =
                DefaultSessionCache(
                    store = cacheStore,
                    connectionIdProvider = { activeConnectionManager.currentProfile()?.id },
                    scope = backgroundScope,
                    json = json,
                )
            val coordinator =
                testConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = modelStore,
                    composerDraftStore = draftStore,
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                    sessionCache = cache,
                )
            coordinator.saveProfile(ConnectionProfile(baseUrl = "http://10.0.0.1:4096/", username = "opencode", password = "a", name = "A"))
            coordinator.saveProfile(ConnectionProfile(baseUrl = "http://10.0.0.2:4096/", username = "opencode", password = "b", name = "B"))
            val a = connectionProfileStore.listProfiles().first { it.name == "A" }
            val b = connectionProfileStore.listProfiles().first { it.name == "B" }
            val identityA = ModelSelectionConnectionIdentity(a.baseUrl, a.username)
            val identityB = ModelSelectionConnectionIdentity(b.baseUrl, b.username)
            val selection = SessionModelSelection(providerId = "openai", modelId = "gpt-4", savedAtEpochMs = 2_000)
            modelStore.saveSession(identityA, "/repo", "ses-1", selection)
            modelStore.saveNewSessionPreference(identityA, "/repo", selection)
            modelStore.saveSession(identityB, "/repo", "ses-2", selection)
            draftStore.save(identityA, "/repo", "ses-1", ComposerDraft(text = "a", savedAtEpochMs = 2_000))
            draftStore.save(identityB, "/repo", "ses-2", ComposerDraft(text = "b", savedAtEpochMs = 2_000))
            listOf(a.id to "/repo", a.id to "/other", b.id to "/repo").forEach { (connectionId, worktree) ->
                cacheStore.commit(
                    SessionCacheKey(connectionId = connectionId, worktree = worktree, kind = SessionCacheKind.SessionList),
                    SessionCacheValue(payloadJson = "[]", schemaVersion = 1),
                    SessionCacheCommitMeta(syncedAtEpochMs = 1),
                )
            }

            coordinator.removeProfile(a.id)

            assertNull(modelStore.loadSession(identityA, "/repo", "ses-1"))
            assertNull(modelStore.loadNewSessionPreference(identityA, "/repo"))
            assertEquals("gpt-4", modelStore.loadSession(identityB, "/repo", "ses-2")?.modelId)
            assertNull(draftStore.load(identityA, "/repo", "ses-1"))
            assertEquals("b", draftStore.load(identityB, "/repo", "ses-2")?.text)
            assertEquals(0L, cacheStore.report(SessionCacheEvictSelector.Connection(a.id)).entryCount)
            assertEquals(1L, cacheStore.report(SessionCacheEvictSelector.Connection(b.id)).entryCount)
            assertEquals(listOf("B"), connectionProfileStore.listProfiles().map { it.name })
        }

    @Test
    fun clearConnectionData_deletesTheFeedbackToken() =
        runTest {
            val json = Json { ignoreUnknownKeys = true }
            val connectionProfileStore = FakeConnectionProfileStore()
            val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
            val tokens = InMemoryFeedbackIdentityStore().apply { writeRefreshToken("refresh-token") }
            val coordinator =
                testConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(),
                    composerDraftStore = InMemoryComposerDraftStore(),
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                    feedbackIdentityStore = tokens,
                )

            coordinator.clearConnectionData()

            assertNull(tokens.readRefreshToken())
        }

    @Test
    fun saveProfile_doesNotEmitWhenEditingInactive() =
        runTest {
            val connectionProfileStore = CatalogConnectionProfileStore()
            val json = Json { ignoreUnknownKeys = true }
            val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
            val coordinator =
                testConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(),
                    composerDraftStore = InMemoryComposerDraftStore(),
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
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
                testConnectionSessionCoordinator(
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
            assertEquals(listOf("stop", "start"), stream.ops.toList())
            assertEquals("Home", connectionProfileStore.activeProfile()?.name)
        }

    @Test
    fun applyProfileChange_isOrderedAndAwaitsStop() =
        runTest {
            val connectionProfileStore = CatalogConnectionProfileStore()
            connectionProfileStore.saveProfile(
                ConnectionProfile(
                    baseUrl = "http://10.0.0.1:4096/",
                    username = "opencode",
                    password = "secret",
                    name = "Home",
                ),
            )
            connectionProfileStore.saveProfile(
                ConnectionProfile(
                    baseUrl = "http://10.0.0.2:4096/",
                    username = "opencode",
                    password = "secret",
                    name = "Work",
                ),
            )
            val home = connectionProfileStore.listProfiles().first { it.name == "Home" }
            val json = Json { ignoreUnknownKeys = true }
            val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
            val order = CopyOnWriteArrayList<String>()
            val enteredStop = CompletableDeferred<Unit>()
            val releaseStop = CompletableDeferred<Unit>()
            val fake = FakeForegroundEventStream()
            val stream =
                object : LiveConnection by fake {
                    override suspend fun stop() {
                        order += "stop"
                        enteredStop.complete(Unit)
                        releaseStop.await()
                        fake.stop()
                    }

                    override fun start() {
                        order += "start"
                        fake.start()
                    }
                }
            val caches =
                object : ProjectionCaches {
                    override fun clearAll() {
                        order += "clearAll"
                    }

                    override fun clearLiveProjections() {
                        order += "clearLiveProjections"
                    }

                    override fun bindProfile(profileId: String?) {
                        order += "bindProfile"
                    }
                }
            val sync =
                object : SyncCoordinator by NoOpSyncCoordinator {
                    override fun beginProfileSwitch(generation: Long) {
                        order += "beginProfileSwitch"
                    }
                }
            val world =
                object : FakeWorldSignals() {
                    override fun emit(signal: WorldSignal) {
                        order += signal.name
                        super.emit(signal)
                    }
                }
            val coordinator =
                testConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(),
                    composerDraftStore = InMemoryComposerDraftStore(),
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                    eventStream = stream,
                    syncCoordinator = sync,
                    worldSignals = world,
                    projectionCaches = caches,
                )
            val switch = backgroundScope.async { coordinator.switchProfile(home.id) }
            enteredStop.await()
            assertEquals(listOf("beginProfileSwitch", "stop"), order.toList())
            releaseStop.complete(Unit)
            switch.await()
            advanceUntilIdle()
            assertEquals(
                listOf(
                    "beginProfileSwitch",
                    "stop",
                    "clearLiveProjections",
                    "bindProfile",
                    "ProfileSwitched",
                    "start",
                ),
                order.toList(),
            )
            assertEquals(1, order.count { it == "ProfileSwitched" })
            assertEquals(1, order.count { it == "stop" })
            assertEquals(1, order.count { it == "start" })
        }

    @Test
    fun switchProfile_signalsTheUiBeforeStopFinishes() =
        runTest {
            val connectionProfileStore = CatalogConnectionProfileStore()
            connectionProfileStore.saveProfile(
                ConnectionProfile(
                    baseUrl = "http://10.0.0.1:4096/",
                    username = "opencode",
                    password = "secret",
                    name = "Home",
                ),
            )
            val home = connectionProfileStore.listProfiles().first { it.name == "Home" }
            val json = Json { ignoreUnknownKeys = true }
            val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
            val enteredStop = CompletableDeferred<Unit>()
            val releaseStop = CompletableDeferred<Unit>()
            val fake = FakeForegroundEventStream()
            val stream =
                object : LiveConnection by fake {
                    override suspend fun stop() {
                        enteredStop.complete(Unit)
                        releaseStop.await()
                        fake.stop()
                    }
                }
            val coordinator =
                testConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(),
                    composerDraftStore = InMemoryComposerDraftStore(),
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                    eventStream = stream,
                )
            val switching = CopyOnWriteArrayList<String>()
            backgroundScope.launch { coordinator.profileSwitching.collect { switching += it } }
            val switch = backgroundScope.async { coordinator.switchProfile(home.id) }

            enteredStop.await()

            assertEquals(listOf(home.id), switching.toList())
            releaseStop.complete(Unit)
            switch.await()
        }

    @Test
    fun concurrentSwitch_isSerializedAndEndsOnLastProfile() =
        runTest {
            val connectionProfileStore = CatalogConnectionProfileStore()
            val json = Json { ignoreUnknownKeys = true }
            val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
            val world = FakeWorldSignals()
            val switched = CopyOnWriteArrayList<WorldSignal>()
            backgroundScope.launch { world.signals.collect { switched += it } }
            val coordinator =
                testConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(),
                    composerDraftStore = InMemoryComposerDraftStore(),
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                    worldSignals = world,
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
            val work = connectionProfileStore.listProfiles().first { it.name == "Work" }
            switched.clear()
            val first = async { coordinator.switchProfile(home.id) }
            val second = async { coordinator.switchProfile(work.id) }
            first.await()
            second.await()
            advanceUntilIdle()
            assertEquals("Work", connectionProfileStore.activeProfile()?.name)
            assertEquals(2, switched.count { it == WorldSignal.ProfileSwitched })
        }

    @Test
    fun passwordChange_sameUrl_restartsStreamAfterAuthFailed() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        val n = attempts.incrementAndGet()
                        if (n == 1) {
                            respond(content = "", status = HttpStatusCode.Unauthorized)
                        } else {
                            respond(
                                content = CONNECTED_EVENT,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                            )
                        }
                    },
                )
            val connectionProfileStore = CatalogConnectionProfileStore()
            val json = Json { ignoreUnknownKeys = true }
            val activeConnectionManager =
                ActiveConnectionManager(
                    connectionProfileStore,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(json),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                )
            val coordinator =
                testConnectionSessionCoordinator(
                    activeConnectionManager = activeConnectionManager,
                    connectionProfileStore = connectionProfileStore,
                    secureCredentialStore = FakeSecureCredentialStore(),
                    sessionModelSelectionStore = FakeSessionModelSelectionStore(),
                    composerDraftStore = InMemoryComposerDraftStore(),
                    httpClient = OpencodeHttpClient(json, activeConnectionManager),
                    sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                    eventStream = stream,
                )
            try {
                coordinator.saveProfile(
                    ConnectionProfile(
                        baseUrl = "http://127.0.0.1:4096/",
                        username = "opencode",
                        password = "old",
                        name = "Local",
                    ),
                    serverProtocol = ServerProtocol.V2,
                )
                val saved = checkNotNull(connectionProfileStore.activeProfile())
                assertNotEquals(
                    ConnectionProfileIdentity.credentialFingerprint("opencode", "old"),
                    ConnectionProfileIdentity.credentialFingerprint("opencode", "new"),
                )
                withTimeout(3_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.AuthFailed }
                }
                coordinator.saveProfile(saved.copy(password = "new"), serverProtocol = ServerProtocol.V2)
                withTimeout(5_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Connected }
                }
                assertTrue(attempts.get() >= 2)
            } finally {
                stream.stop()
                client.close()
            }
        }
}

private const val CONNECTED_EVENT =
    "data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n"

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

private fun preferencesDataStore(
    scope: CoroutineScope,
    name: String,
) = PreferenceDataStoreFactory.create(
    scope = scope,
    produceFile = { Files.createTempDirectory("coordinator-$name").resolve("preferences.preferences_pb").toFile() },
)
