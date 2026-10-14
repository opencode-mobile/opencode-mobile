package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DefaultForegroundEventStreamTest {
    @Test
    fun coldStart_waitsForProbeBeforeOpeningV2Lease() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val opened = CompletableDeferred<Unit>()
            val probeEntered = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        opened.complete(Unit)
                        respond(
                            content = CONNECTED_V1,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing {
                        probeEntered.complete(Unit)
                        delay(500)
                        ServerProtocol.V2
                    },
                )
            manager.applyProfile(sampleProfile)
            assertEquals(null, manager.currentProtocol())

            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                probeEntered.await()
                assertTrue(requests.isEmpty())
                withTimeout(5_000) { opened.await() }
                assertTrue(
                    requests
                        .single()
                        .url.encodedPath
                        .contains("/api/event"),
                )
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun start_opensLeasesWithoutWaitingForWorktrees() =
        runBlocking {
            val opened = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        opened.complete(Unit)
                        respond(
                            content = CONNECTED_V1,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = HoldingProfileStore(hold, initial = listOf("/repo"), next = listOf("/repo"))
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V1 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(2_000) { opened.await() }
            } finally {
                hold.complete(Unit)
                stream.stop()
                client.close()
            }
        }

    @Test
    fun cachedProtocol_opensWithoutWaitingForProbe() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val opened = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        opened.complete(Unit)
                        respond(
                            content = CONNECTED_V1,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = FakeProfileStore()
            val probeEntered = CompletableDeferred<Unit>()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing {
                        probeEntered.complete(Unit)
                        delay(5_000)
                        ServerProtocol.V1
                    },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)

            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(1_000) { opened.await() }
                assertTrue(
                    requests
                        .single()
                        .url.encodedPath
                        .contains("/api/event"),
                )
                assertTrue(!probeEntered.isCompleted)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun v1WithWorktrees_opensGlobalAndInstanceEvent() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val openedBoth = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        if (requests.any { it.url.encodedPath.contains("/global/event") } &&
                            requests.any { it.url.encodedPath.endsWith("/event") && !it.url.encodedPath.contains("global") }
                        ) {
                            openedBoth.complete(Unit)
                        }
                        respond(
                            content = CONNECTED_V1,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = FakeProfileStore(worktrees = listOf("/Users/ivan/Projects/permission-test"))
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V1 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(2_000) { openedBoth.await() }
                val paths = requests.map { it.url.encodedPath }
                assertTrue(paths.any { it.contains("/global/event") })
                assertTrue(paths.any { it.endsWith("/event") && !it.contains("global") })
                assertTrue(
                    requests.any {
                        it.url.parameters["directory"] == "/Users/ivan/Projects/permission-test"
                    },
                )
                assertTrue(requests.none { it.url.encodedPath.contains("/api/event") })
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun v2WithWorktrees_opensOnlyApiEvent() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val opened = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        opened.complete(Unit)
                        respond(
                            content = "data: {\"type\":\"server.connected\",\"data\":{}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = FakeProfileStore(worktrees = listOf("/Users/ivan/Projects/permission-test"))
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(2_000) { opened.await() }
                assertEquals(1, requests.size)
                assertTrue(
                    requests
                        .single()
                        .url.encodedPath
                        .contains("/api/event"),
                )
                assertEquals(null, requests.single().url.parameters["directory"])
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun connectFailure_retriesUntilConnected() =
        runBlocking {
            val opens = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        val n = opens.incrementAndGet()
                        if (n == 1) {
                            throw java.net.ConnectException("Failed to connect")
                        }
                        respond(
                            content = "data: {\"type\":\"server.connected\",\"data\":{}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(5_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Connected }
                }
                assertTrue(opens.get() >= 2)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun http401_setsAuthFailedAndStopsRetrying() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        attempts.incrementAndGet()
                        respond(content = "", status = HttpStatusCode.Unauthorized)
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(3_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.AuthFailed }
                }
                delay(2_000)
                assertEquals(1, attempts.get())
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun missingServerConnectedTimesOutAndRetries() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        attempts.incrementAndGet()
                        respond(
                            content = HEARTBEAT_V1,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(8_000) {
                    while (attempts.get() < 2) {
                        delay(50)
                    }
                }
                assertTrue(attempts.get() >= 2)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun reconnectWakeDuringBackoffRetriesImmediately() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        val n = attempts.incrementAndGet()
                        if (n == 1) {
                            respond(content = "", status = HttpStatusCode.NotFound)
                        } else {
                            val channel = ByteChannel(autoFlush = true)
                            channel.writeStringUtf8(CONNECTED_V1)
                            respond(
                                content = channel,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                            )
                        }
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(3_000) {
                    while (attempts.get() < 1) {
                        delay(10)
                    }
                }
                stream.onReconnectWake()
                withTimeout(3_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Connected }
                }
                assertEquals(2, attempts.get())
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun http403_setsAuthFailedAndStopsRetrying() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        attempts.incrementAndGet()
                        respond(content = "", status = HttpStatusCode.Forbidden)
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(3_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.AuthFailed }
                }
                delay(1_500)
                assertEquals(1, attempts.get())
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun http404_fastRetriesWhileProtocolUnconfirmed() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        attempts.incrementAndGet()
                        respond(content = "", status = HttpStatusCode.NotFound)
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { error("probe failed") },
                )
            manager.applyProfile(sampleProfile)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                delay(2_500)
                assertTrue(attempts.get() >= 3)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun firstFrameHeartbeat_failsImmediatelyAndRetries() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        attempts.incrementAndGet()
                        val channel = ByteChannel(autoFlush = true)
                        channel.writeStringUtf8(HEARTBEAT_V1)
                        respond(
                            content = channel,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(2_500) {
                    while (attempts.get() < 2) {
                        delay(50)
                    }
                }
                assertTrue(attempts.get() >= 2)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun http200WithoutFirstFrame_doesNotMarkLive() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        val channel = ByteChannel(autoFlush = true)
                        respond(
                            content = channel,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                delay(1_000)
                assertTrue(stream.connectionState.value != EventStreamConnectionState.Connected)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun connectBudgetExceeded_retriesAndEvicts() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val evicted = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        attempts.incrementAndGet()
                        awaitCancellation()
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi =
                        EventsApi(
                            httpClient = client,
                            onEvict = { evicted.incrementAndGet() },
                            connectBudgetMs = 80L,
                        ),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.start()
                withTimeout(4_000) {
                    while (attempts.get() < 2) {
                        delay(20)
                    }
                }
                assertTrue(attempts.get() >= 2)
                assertTrue(evicted.get() >= 1)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun t3Connected_marksTheSocketFullyLiveSoHomeCanCatchUp() =
        runBlocking {
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V1 },
                )
            manager.applyProfile(
                ConnectionProfile(
                    baseUrl = "https://t3.example/",
                    username = ConnectionEngine.T3_USERNAME,
                    engine = ConnectionEngine.T3,
                ),
            )
            val client = HttpClient(MockEngine { respond("") })
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.publishExternalConnectionState(EventStreamConnectionState.Connecting)
                val connecting = stream.snapshot.value
                assertEquals(EventStreamConnectionState.Connecting, connecting.connectionState)
                assertEquals(listOf("t3"), connecting.requiredTargets)
                assertTrue(connecting.liveTargets.isEmpty())
                assertFalse(canCatchUp(StreamOccupancy.Active, connecting))

                stream.publishExternalConnectionState(EventStreamConnectionState.Connected)
                val connected = stream.snapshot.value
                assertEquals(listOf("t3"), connected.requiredTargets)
                assertEquals(listOf("t3"), connected.liveTargets)
                assertTrue(connected.isFullyLive())
                assertTrue(canCatchUp(StreamOccupancy.Active, connected))

                stream.publishExternalConnectionState(EventStreamConnectionState.Disconnected)
                assertFalse(stream.snapshot.value.isFullyLive())
                assertFalse(canCatchUp(StreamOccupancy.Active, stream.snapshot.value))
            } finally {
                client.close()
            }
        }

    @Test
    fun opencodeProfile_ignoresExternalT3ConnectionState() =
        runBlocking {
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V1 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)
            val client = HttpClient(MockEngine { respond("") })
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                )
            try {
                stream.publishExternalConnectionState(EventStreamConnectionState.Connected)
                assertEquals(EventStreamConnectionState.Idle, stream.connectionState.value)
                assertTrue(stream.snapshot.value.requiredTargets.isEmpty())
                assertFalse(canCatchUp(StreamOccupancy.Active, stream.snapshot.value))
            } finally {
                client.close()
            }
        }

    private val sampleProfile =
        ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "x")

    private companion object {
        private const val CONNECTED_V1 =
            "data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n"
        private const val HEARTBEAT_V1 =
            "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n"
    }

    private class HoldingProfileStore(
        private val hold: CompletableDeferred<Unit>,
        private val initial: List<String>,
        private val next: List<String>,
    ) : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null
        private val resolves = AtomicInteger(0)
        val firstResolveEntered = CompletableDeferred<Unit>()
        val secondResolveEntered = CompletableDeferred<Unit>()

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            this.profile = profile
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> {
            val n = resolves.incrementAndGet()
            if (n == 1) {
                firstResolveEntered.complete(Unit)
                hold.await()
                return initial
            }
            if (n == 2) {
                secondResolveEntered.complete(Unit)
            }
            return next
        }

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

        override fun configurationRevisions(): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
    }

    private class FakeProfileStore(
        private val worktrees: List<String> = emptyList(),
    ) : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            this.profile = profile
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = worktrees

        override suspend fun addWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) {}

        override suspend fun removeWorktree(
            serverBaseUrl: String,
            worktree: String,
        ) {}

        override suspend fun setAddedWorktrees(
            serverBaseUrl: String,
            worktrees: List<String>,
        ) {}

        override suspend fun clearAll() {
            profile = null
        }
    }
}
