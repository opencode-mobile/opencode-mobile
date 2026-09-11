package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.EventsApi
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DefaultForegroundEventStreamTest {
    @Test
    fun coldStart_opensV1HintWithoutWaitingForProbe() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val opened = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        opened.complete(Unit)
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
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
                        delay(5_000)
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
                withTimeout(1_000) { opened.await() }
                assertTrue(requests.single().url.encodedPath.contains("/global/event"))
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
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
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
    fun cachedProtocol_opensMatchingSsePath() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val opened = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        opened.complete(Unit)
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V1 },
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
                withTimeout(5_000) { opened.await() }
                assertTrue(requests.single().url.encodedPath.contains("/api/event"))
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
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
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
                assertTrue(requests.single().url.encodedPath.contains("/api/event"))
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

    private val sampleProfile =
        ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "x")


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

        override fun configurationRevisions(): kotlinx.coroutines.flow.Flow<Unit> =
            kotlinx.coroutines.flow.emptyFlow()
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
