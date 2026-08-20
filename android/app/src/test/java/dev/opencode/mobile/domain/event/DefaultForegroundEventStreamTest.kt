package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import dev.opencode.mobile.domain.connection.FakeDeviceNetworkPath
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    fun unavailablePath_doesNotOpenSseUntilReady() =
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
            manager.applyProfile(sampleProfile)
            val path = FakeDeviceNetworkPath(DeviceNetworkPathStatus.Unavailable)
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                    deviceNetworkPath = path,
                )
            try {
                stream.start()
                delay(80)
                assertTrue(requests.isEmpty())
                path.set(DeviceNetworkPathStatus.Ready)
                withTimeout(1_000) { opened.await() }
                assertTrue(requests.single().url.encodedPath.contains("/global/event"))
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun idleTimeout_reconnectsWhenStreamGoesSilent() =
        runBlocking {
            val opens = AtomicInteger(0)
            val secondOpen = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        val n = opens.incrementAndGet()
                        if (n == 1) {
                            val channel = ByteChannel(autoFlush = true)
                            channel.writeStringUtf8("data: {\"type\":\"server.connected\",\"data\":{}}\n\n")
                            respond(
                                content = channel,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                            )
                        } else {
                            secondOpen.complete(Unit)
                            respond(
                                content = "data: {\"type\":\"server.heartbeat\",\"data\":{}}\n\n",
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
                    idleTimeoutMs = 150,
                )
            try {
                stream.start()
                withTimeout(2_000) { secondOpen.await() }
                assertTrue(opens.get() >= 2)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun idleTimeout_doesNotReconnectWhenSseCommentsArrive() =
        runBlocking {
            val opens = AtomicInteger(0)
            val commentsSent = CompletableDeferred<Unit>()
            val writerJob = Job()
            val client =
                HttpClient(
                    MockEngine {
                        opens.incrementAndGet()
                        val channel = ByteChannel(autoFlush = true)
                        CoroutineScope(Dispatchers.Unconfined + writerJob).launch {
                            channel.writeStringUtf8("data: {\"type\":\"server.connected\",\"data\":{}}\n\n")
                            repeat(8) {
                                delay(40)
                                channel.writeStringUtf8(": keep-alive\n")
                            }
                            commentsSent.complete(Unit)
                        }
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
                    idleTimeoutMs = 150,
                )
            try {
                stream.start()
                withTimeout(2_000) { commentsSent.await() }
                assertEquals(1, opens.get())
            } finally {
                writerJob.cancel()
                stream.stop()
                client.close()
            }
        }

    @Test
    fun start_isIdempotentWhileStreamIsRunning() =
        runBlocking {
            val openCount = AtomicInteger(0)
            val opened = CompletableDeferred<Unit>()
            val channel = ByteChannel(autoFlush = true)
            val client =
                HttpClient(
                    MockEngine {
                        openCount.incrementAndGet()
                        opened.complete(Unit)
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
                withTimeout(5_000) { opened.await() }
                stream.start()
                delay(50)
                assertEquals(1, openCount.get())
            } finally {
                stream.stop()
                channel.close()
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
            val store = FakeProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
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
                delay(50)
                assertEquals(1, requests.size)
                assertTrue(requests.single().url.encodedPath.contains("/api/event"))
                assertEquals(null, requests.single().url.parameters["directory"])
            } finally {
                stream.stop()
                client.close()
            }
        }

    private val sampleProfile =
        ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "x")

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
