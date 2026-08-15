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
import kotlinx.coroutines.launch
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
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
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
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing { ServerProtocol.V1 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)

            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
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
    fun start_isIdempotentWhileStreamIsRunning() =
        runBlocking {
            val openCount = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine { request ->
                        openCount.incrementAndGet()
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                )
            try {
                stream.start()
                withTimeout(5_000) {
                    while (openCount.get() < 1) {
                        delay(10)
                    }
                }
                stream.start()
                delay(50)
                assertEquals(1, openCount.get())
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun retryAfterStreamEnd_doesNotPublishDisconnected() =
        runBlocking {
            val opens = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        opens.incrementAndGet()
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val manager =
                ActiveConnectionManager(
                    FakeProfileStore(),
                    ConnectionProbing { ServerProtocol.V2 },
                )
            manager.applyProfile(sampleProfile)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                )
            val states = mutableListOf<EventStreamConnectionState>()
            val collectJob =
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    stream.connectionState.collect { states.add(it) }
                }
            try {
                stream.start()
                withTimeout(5_000) {
                    while (opens.get() < 2) {
                        delay(20)
                    }
                }
                assertTrue(states.contains(EventStreamConnectionState.Connected))
                assertTrue(
                    states.none { it == EventStreamConnectionState.Disconnected },
                )
            } finally {
                stream.stop()
                collectJob.cancel()
                client.close()
            }
        }

    private val sampleProfile =
        ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "x")

    private class FakeProfileStore : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            this.profile = profile
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

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
