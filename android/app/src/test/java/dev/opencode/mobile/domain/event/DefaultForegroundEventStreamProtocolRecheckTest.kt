package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** A server replaced at the same address by the other opencode protocol (#828). */
class DefaultForegroundEventStreamProtocolRecheckTest {
    @Test
    fun savedV1_serverNowV2_movesToApiEventAndSavesProfile() =
        runBlocking {
            val requests = Collections.synchronizedList(mutableListOf<String>())
            val client = v2ServerClient(requests)
            val store = SavingProfileStore()
            val manager = ActiveConnectionManager(store, ConnectionProbing { ServerProtocol.V2 })
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)
            val stream = stream(client, manager, store)
            try {
                stream.start()
                withTimeout(5_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Connected }
                }
                assertTrue(requests.contains("/api/event"))
                assertEquals(ServerProtocol.V2, manager.currentProtocol())
                assertEquals(ServerProtocol.V2, store.lastSaved?.serverProtocol)
                assertEquals(ServerProtocol.V2, stream.snapshot.value.config?.protocol)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun http500OnSavedProtocol_rechecksAndFollowsDetectedProtocol() =
        runBlocking {
            val requests = Collections.synchronizedList(mutableListOf<String>())
            val client = v2ServerClient(requests)
            val store = SavingProfileStore()
            val probes = AtomicInteger(0)
            // The start check still sees v1; the next one, after the v1 paths answer 500, sees v2.
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing {
                        if (probes.incrementAndGet() == 1) ServerProtocol.V1 else ServerProtocol.V2
                    },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)
            val stream = stream(client, manager, store, recheckIntervalMs = 0)
            try {
                stream.start()
                withTimeout(10_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Connected }
                }
                assertTrue(probes.get() >= 2)
                assertTrue(requests.contains("/global/event"))
                assertTrue(requests.contains("/api/event"))
                assertEquals(ServerProtocol.V2, store.lastSaved?.serverProtocol)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun noProtocolAnswers_showsDisconnectedInsteadOfConnecting() =
        runBlocking {
            val client = v2ServerClient(mutableListOf())
            val store = SavingProfileStore()
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { throw ServerProbeError.Http(statusCode = 502, message = "Bad Gateway") },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)
            val stream = stream(client, manager, store)
            try {
                stream.start()
                withTimeout(5_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Disconnected }
                }
                assertEquals(ServerProtocol.V1, manager.currentProtocol())
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun rejectedCredential_isNotAProtocolChange() =
        runBlocking {
            val client = HttpClient(MockEngine { respond(content = "", status = HttpStatusCode.Unauthorized) })
            val store = SavingProfileStore()
            val probes = AtomicInteger(0)
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing {
                        probes.incrementAndGet()
                        throw ServerProbeError.Http(statusCode = 401, message = "Unauthorized")
                    },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)
            val stream = stream(client, manager, store, recheckIntervalMs = 0)
            try {
                stream.start()
                withTimeout(5_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.AuthFailed }
                }
                repeat(3) {
                    stream.onReconnectWake()
                    delay(200)
                }
                // Only the start check probed; the 401s on the event paths did not.
                assertEquals(1, probes.get())
                assertEquals(EventStreamConnectionState.AuthFailed, stream.connectionState.value)
                assertEquals(ServerProtocol.V1, manager.currentProtocol())
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun reconnectAfterDrop_rechecksAndFollowsServerBackToV1() =
        runBlocking {
            val requests = Collections.synchronizedList(mutableListOf<String>())
            // Like opencode 1.19, the v1 server also answers `/api/event`, so only a check finds it.
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request.url.encodedPath
                        when (request.url.encodedPath) {
                            "/api/event" -> eventStream(CONNECTED_V2)
                            "/global/event" -> eventStream(CONNECTED_V1)
                            else -> respond(content = "", status = HttpStatusCode.NotFound)
                        }
                    },
                )
            val store = SavingProfileStore()
            val probes = AtomicInteger(0)
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing {
                        if (probes.incrementAndGet() == 1) ServerProtocol.V2 else ServerProtocol.V1
                    },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            val stream = stream(client, manager, store, recheckIntervalMs = 0)
            try {
                stream.start()
                withTimeout(10_000) {
                    stream.snapshot.first { snapshot ->
                        snapshot.config?.protocol == ServerProtocol.V1 &&
                            snapshot.connectionState == EventStreamConnectionState.Connected
                    }
                }
                assertTrue(requests.contains("/global/event"))
                assertEquals(ServerProtocol.V1, store.lastSaved?.serverProtocol)
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun launchedWhileServerDown_thenV1Starts_recheckInsideWindowIsDeferredNotDropped() =
        runBlocking {
            val requests = Collections.synchronizedList(mutableListOf<String>())
            val apiEventAttempts = AtomicInteger(0)
            // The server is down for the first attempts, then v1 starts; v1 also answers `/api/event`.
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request.url.encodedPath
                        when (request.url.encodedPath) {
                            "/api/event" ->
                                if (apiEventAttempts.incrementAndGet() <= 2) {
                                    throw java.net.ConnectException("Connection refused")
                                } else {
                                    // Stays open: the recovery is the only hint that v1 is back.
                                    openEventStream(CONNECTED_V2)
                                }
                            "/global/event" -> eventStream(CONNECTED_V1)
                            else -> respond(content = "", status = HttpStatusCode.NotFound)
                        }
                    },
                )
            val store = SavingProfileStore()
            val probes = AtomicInteger(0)
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing {
                        if (probes.incrementAndGet() == 1) {
                            throw ServerProbeError.Transport(java.net.ConnectException("Connection refused"))
                        }
                        ServerProtocol.V1
                    },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V2)
            // The stream connects ~3 s in, inside this window, so its recheck must wait for the window.
            val stream = stream(client, manager, store, recheckIntervalMs = 5_000)
            try {
                stream.start()
                withTimeout(5_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Disconnected }
                }
                withTimeout(15_000) {
                    stream.snapshot.first { snapshot ->
                        snapshot.config?.protocol == ServerProtocol.V1 &&
                            snapshot.connectionState == EventStreamConnectionState.Connected
                    }
                }
                assertTrue(requests.contains("/global/event"))
                assertEquals(ServerProtocol.V1, store.lastSaved?.serverProtocol)
            } finally {
                stream.stop()
                client.close()
            }
        }

    /** opencode v2: the v1 event paths answer 500, `/api/event` streams. */
    private fun v2ServerClient(requests: MutableList<String>): HttpClient =
        HttpClient(
            MockEngine { request: HttpRequestData ->
                requests += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/api/event" -> eventStream(CONNECTED_V2)
                    else -> respond(content = "", status = HttpStatusCode.InternalServerError)
                }
            },
        )

    private suspend fun MockRequestHandleScope.openEventStream(firstFrame: String): HttpResponseData {
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8(firstFrame)
        return respond(
            content = channel,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )
    }

    private fun MockRequestHandleScope.eventStream(body: String): HttpResponseData =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
        )

    private fun stream(
        client: HttpClient,
        manager: ActiveConnectionManager,
        store: ConnectionProfileStore,
        recheckIntervalMs: Long = ProtocolRecheckPolicy.MIN_INTERVAL_MS,
    ) = DefaultForegroundEventStream(
        eventsApi = EventsApi(client),
        eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
        diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
        activeConnectionManager = manager,
        connectionProfileStore = store,
        idleTimeoutMs = 45_000L,
        protocolRecheckIntervalMs = recheckIntervalMs,
    )

    private val sampleProfile =
        ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "x")

    private companion object {
        const val CONNECTED_V1 = "data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n"
        const val CONNECTED_V2 = "data: {\"type\":\"server.connected\",\"data\":{}}\n\n"
    }

    private class SavingProfileStore : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null

        @Volatile
        var lastSaved: ConnectionProfile? = null
            private set

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            lastSaved = profile
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
