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
import dev.opencode.mobile.domain.message.ConnectionEpoch
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
    fun stopThenStart_reopensCachedDirectoriesWithoutWaitingForWorktrees() =
        runBlocking {
            val openedResume = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            val stallNext = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (stallNext.get() > 0 && request.url.parameters["directory"] == "/repo") {
                            openedResume.complete(Unit)
                        }
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = CacheThenHoldProfileStore(worktrees = listOf("/repo"), hold = hold, stallNext = stallNext)
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
                withTimeout(2_000) {
                    stream.snapshot.first { snap -> snap.requiredTargets.contains("event:/repo") }
                }
                stallNext.incrementAndGet()
                stream.stop()
                stream.start()
                withTimeout(2_000) { openedResume.await() }
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
    fun unavailablePath_overlappingStartDuringWait_keepsNewestTargets() =
        runBlocking<Unit> {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = MutableWorktreeTestStore(listOf("/old"))
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V1 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)
            val path = FakeDeviceNetworkPath(DeviceNetworkPathStatus.Ready)
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
                withTimeout(2_000) {
                    stream.snapshot.first { snap -> snap.requiredTargets.contains("event:/old") }
                }
                stream.stop()
                withTimeout(2_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Idle }
                }

                path.set(DeviceNetworkPathStatus.Unavailable)
                store.setWorktrees(listOf("/new"))
                stream.start()
                withTimeout(2_000) {
                    stream.snapshot.first { snap -> snap.requiredTargets.contains("event:/old") }
                }
                stream.start()
                withTimeout(2_000) {
                    stream.snapshot.first { snap -> snap.requiredTargets.contains("event:/new") }
                }
                path.set(DeviceNetworkPathStatus.Ready)
                withTimeout(2_000) {
                    stream.snapshot.first { snap ->
                        snap.requiredTargets.contains("event:/new") &&
                            snap.requiredTargets.none { it == "event:/old" }
                    }
                }
                delay(80)
                val required = stream.snapshot.value.requiredTargets
                assertTrue(required.contains("event:/new"))
                assertTrue(required.none { it == "event:/old" })
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun configurationRevision_emittedDuringInitialResolve_isNotLost() =
        runBlocking<Unit> {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = ReplayZeroRevisionStore(first = listOf("/old"), next = listOf("/new"))
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
                withTimeout(2_000) { store.firstResolveEntered.await() }
                store.setWorktrees(listOf("/new"))
                assertTrue(store.emitRevision())
                store.releaseFirstResolve()
                withTimeout(2_000) {
                    stream.snapshot.first { snap ->
                        snap.requiredTargets.contains("event:/new") &&
                            snap.requiredTargets.none { it == "event:/old" }
                    }
                }
            } finally {
                store.releaseFirstResolve()
                stream.stop()
                client.close()
            }
        }

    @Test
    fun newerIdempotentStart_preventsOlderStaleResolve() =
        runBlocking<Unit> {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = HeldRevisionProfileStore(initial = listOf("/old"))
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
                withTimeout(2_000) {
                    stream.snapshot.first { snap -> snap.requiredTargets.contains("event:/old") }
                }
                store.holdNextAddedWorktrees = true
                store.emitRevision()
                withTimeout(2_000) { store.revisionResolveEntered.await() }
                val resolvesBeforeNewerStart = store.resolveCount()
                stream.start()
                withTimeout(2_000) {
                    while (store.resolveCount() <= resolvesBeforeNewerStart) {
                        delay(10)
                    }
                }
                store.setWorktrees(listOf("/stale"))
                store.releaseRevisionResolve()
                delay(80)
                val required = stream.snapshot.value.requiredTargets
                assertTrue(required.contains("event:/old"))
                assertTrue(required.none { it == "event:/stale" })
            } finally {
                store.releaseRevisionResolve()
                stream.stop()
                client.close()
            }
        }

    @Test
    fun configurationRevision_afterPathWait_appliesCurrentDirectories() =
        runBlocking<Unit> {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = HeldRevisionProfileStore(initial = listOf("/old"))
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V1 },
                )
            manager.applyProfile(sampleProfile, serverProtocol = ServerProtocol.V1)
            val path = FakeDeviceNetworkPath(DeviceNetworkPathStatus.Ready)
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
                withTimeout(2_000) {
                    stream.snapshot.first { snap -> snap.requiredTargets.contains("event:/old") }
                }
                stream.stop()
                withTimeout(2_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Idle }
                }
                path.set(DeviceNetworkPathStatus.Unavailable)
                store.setWorktrees(listOf("/new"))
                stream.start()
                withTimeout(2_000) {
                    stream.snapshot.first { snap -> snap.requiredTargets.contains("event:/old") }
                }
                path.set(DeviceNetworkPathStatus.Ready)
                store.emitRevision()
                withTimeout(2_000) {
                    stream.snapshot.first { snap ->
                        snap.requiredTargets.contains("event:/new") &&
                            snap.requiredTargets.none { it == "event:/old" }
                    }
                }
            } finally {
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

    @Test
    fun stopThenStart_doesNotWaitForStaleOverlappingStartApply() =
        runBlocking<Unit> {
            val channels = CopyOnWriteArrayList<ByteChannel>()
            val client =
                HttpClient(
                    MockEngine {
                        val channel = ByteChannel(autoFlush = true)
                        channels += channel
                        channel.writeStringUtf8(
                            "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
                        )
                        respond(
                            content = channel,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = StallNextProfileStore()
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
                withTimeout(2_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Connected }
                }
                store.arm()
                stream.start()
                withTimeout(2_000) { store.stalled.await() }
                stream.stop()
                withTimeout(2_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Idle }
                }
                val opensAfterStop = channels.size
                stream.start()
                withTimeout(2_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Connected }
                }
                assertTrue(channels.size > opensAfterStop)
            } finally {
                stream.stop()
                store.release()
                channels.forEach { it.close() }
                client.close()
            }
        }

    @Test
    fun startDuringHeldConfigResolve_usesPendingConfigNotStale() =
        runBlocking {
            val requests = CopyOnWriteArrayList<HttpRequestData>()
            val opened = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        if (request.url.parameters["directory"] == "/new") {
                            opened.complete(Unit)
                        }
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = HoldingProfileStore(hold, initial = listOf("/old"), next = listOf("/new"))
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
                withTimeout(2_000) { store.firstResolveEntered.await() }
                stream.start()
                withTimeout(2_000) { store.secondResolveEntered.await() }
                hold.complete(Unit)
                withTimeout(2_000) { opened.await() }
                withTimeout(2_000) {
                    stream.snapshot.first { snap ->
                        snap.requiredTargets.contains("event:/new") &&
                            snap.requiredTargets.none { it == "event:/old" }
                    }
                }
                val directories = requests.mapNotNull { it.url.parameters["directory"] }
                assertTrue(directories.contains("/new"))
                assertTrue(directories.none { it == "/old" })
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun startThenReconnect_bumpsEpochOnce() =
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
            val epoch = ConnectionEpoch()
            val stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                    idleTimeoutMs = 150,
                    connectionEpoch = epoch,
                )
            try {
                val before = epoch.current()
                stream.start()
                withTimeout(2_000) { secondOpen.await() }
                assertEquals(before + 1, epoch.current())
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun concurrentGlobalAndInstanceLiveDead_doesNotDropOrDoubleGap() =
        runBlocking {
            val channels = java.util.concurrent.ConcurrentHashMap<String, io.ktor.utils.io.ByteChannel>()
            val reconnects = java.util.concurrent.CopyOnWriteArrayList<Long>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val target =
                            when {
                                request.url.encodedPath.contains("global/event") -> "global/event"
                                request.url.parameters["directory"] != null ->
                                    "event:${request.url.parameters["directory"]}"
                                else -> "event"
                            }
                        val channel = io.ktor.utils.io.ByteChannel(autoFlush = true)
                        channels[target] = channel
                        channel.writeStringUtf8(
                            "data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n",
                        )
                        respond(
                            content = channel,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store = FakeProfileStore(worktrees = listOf("/repo"))
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
                    idleTimeoutMs = 60_000,
                )
            val collectJob =
                launch {
                    stream.events.collect { event ->
                        if (event.type == "stream.reconnected") {
                            reconnects += stream.snapshot.value.gap?.generation ?: 0L
                        }
                    }
                }
            try {
                stream.start()
                withTimeout(2_000) {
                    while (stream.snapshot.value.liveTargets.toSet() !=
                        setOf("global/event", "event:/repo")
                    ) {
                        delay(10)
                    }
                }
                val gapIds = java.util.concurrent.CopyOnWriteArrayList<Long>()
                repeat(12) {
                    channels["event:/repo"]?.close()
                    withTimeout(2_000) {
                        while (stream.snapshot.value.gap?.reason != LiveGapReason.LeaseLost ||
                            "event:/repo" in stream.snapshot.value.liveTargets
                        ) {
                            delay(5)
                        }
                    }
                    gapIds += stream.snapshot.value.gap?.generation ?: 0L
                    withTimeout(2_000) {
                        while (stream.snapshot.value.liveTargets.toSet() !=
                            setOf("global/event", "event:/repo")
                        ) {
                            delay(10)
                        }
                    }
                }
                assertEquals(12, gapIds.size)
                assertEquals(gapIds.toSet().size, gapIds.size)
                assertEquals(reconnects.toSet().size, reconnects.size)
                val snapshot = stream.snapshot.value
                assertEquals(setOf("global/event", "event:/repo"), snapshot.liveTargets.toSet())
                assertTrue(snapshot.requiredTargets.containsAll(snapshot.liveTargets))
            } finally {
                stream.stop()
                collectJob.cancel()
                client.close()
            }
        }

    @Test
    fun concurrentStartStop_doesNotDeadlockOrLeakOccupancy() =
        runBlocking<Unit> {
            val client =
                HttpClient(
                    MockEngine {
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
                withTimeout(2_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Connected }
                }
                val workers =
                    (1..8).map {
                        launch(Dispatchers.Default) {
                            repeat(12) {
                                stream.start()
                                stream.stop()
                                stream.start()
                            }
                        }
                    }
                withTimeout(8_000) { workers.joinAll() }
                stream.stop()
                withTimeout(2_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Idle }
                }
                stream.start()
                withTimeout(2_000) {
                    stream.connectionState.first { it == EventStreamConnectionState.Connected }
                }
            } finally {
                stream.stop()
                client.close()
            }
        }

    @Test
    fun stopThenStart_doesNotReuseCachedDirectoriesAcrossProfiles() =
        runBlocking {
            val requests = CopyOnWriteArrayList<HttpRequestData>()
            val holdB = CompletableDeferred<Unit>()
            val stallB = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        respond(
                            content = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store =
                PerUrlHoldingProfileStore(
                    worktreesByUrl =
                        mapOf(
                            "http://127.0.0.1:4096/" to listOf("/repo-a"),
                            "http://10.0.0.2:5096/" to listOf("/repo-b"),
                        ),
                    hold = holdB,
                    stallNext = stallB,
                    stallUrlContains = "5096",
                )
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V1 },
                )
            val profileA =
                ConnectionProfile(
                    baseUrl = "http://127.0.0.1:4096/",
                    username = "opencode",
                    password = "x",
                    id = "profile-a",
                )
            val profileB =
                ConnectionProfile(
                    baseUrl = "http://10.0.0.2:5096/",
                    username = "opencode",
                    password = "x",
                    id = "profile-b",
                )
            manager.applyProfile(profileA, serverProtocol = ServerProtocol.V1)
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
                withTimeout(2_000) {
                    stream.snapshot.first { snap -> snap.requiredTargets.contains("event:/repo-a") }
                }
                stream.stop()
                manager.applyProfile(profileB, serverProtocol = ServerProtocol.V1)
                stallB.incrementAndGet()
                requests.clear()
                stream.start()
                withTimeout(2_000) {
                    stream.snapshot.first { snap ->
                        snap.config?.profileId == "profile-b" &&
                            snap.requiredTargets.contains("global/event")
                    }
                }
                delay(80)
                val directories = requests.mapNotNull { it.url.parameters["directory"] }
                assertTrue(directories.none { it == "/repo-a" })
                assertTrue(requests.any { it.url.encodedPath.contains("/global/event") })
                assertTrue(requests.none { it.url.parameters["directory"] == "/repo-b" })
            } finally {
                holdB.complete(Unit)
                stream.stop()
                client.close()
            }
        }

    @Test
    fun stopThenStart_v2DoesNotOpenPreviousProfileV1Directories() =
        runBlocking {
            val requests = CopyOnWriteArrayList<HttpRequestData>()
            val holdB = CompletableDeferred<Unit>()
            val stallB = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        respond(
                            content = "data: {\"type\":\"server.connected\",\"data\":{}}\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val store =
                PerUrlHoldingProfileStore(
                    worktreesByUrl =
                        mapOf(
                            "http://127.0.0.1:4096/" to listOf("/repo-a"),
                            "http://10.0.0.2:5096/" to listOf("/repo-b"),
                        ),
                    hold = holdB,
                    stallNext = stallB,
                    stallUrlContains = "5096",
                )
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { ServerProtocol.V2 },
                )
            val profileA =
                ConnectionProfile(
                    baseUrl = "http://127.0.0.1:4096/",
                    username = "opencode",
                    password = "x",
                    id = "profile-a",
                )
            val profileB =
                ConnectionProfile(
                    baseUrl = "http://10.0.0.2:5096/",
                    username = "opencode",
                    password = "x",
                    id = "profile-b",
                )
            manager.applyProfile(profileA, serverProtocol = ServerProtocol.V1)
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
                withTimeout(2_000) {
                    stream.snapshot.first { snap -> snap.requiredTargets.contains("event:/repo-a") }
                }
                stream.stop()
                manager.applyProfile(profileB, serverProtocol = ServerProtocol.V2)
                stallB.incrementAndGet()
                requests.clear()
                stream.start()
                withTimeout(2_000) {
                    stream.snapshot.first { snap ->
                        snap.config?.protocol == ServerProtocol.V2 &&
                            snap.requiredTargets.contains("api/event")
                    }
                }
                delay(80)
                assertTrue(requests.all { it.url.encodedPath.contains("/api/event") })
                assertTrue(requests.none { it.url.parameters["directory"] != null })
            } finally {
                holdB.complete(Unit)
                stream.stop()
                client.close()
            }
        }

    private val sampleProfile =
        ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = "x")

    private class StallNextProfileStore : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null
        private val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val hold = CompletableDeferred<Unit>()
        val stalled = CompletableDeferred<Unit>()

        fun arm() {
            armed.set(true)
        }

        fun release() {
            hold.complete(Unit)
        }

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            this.profile = profile
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> {
            if (armed.compareAndSet(true, false)) {
                stalled.complete(Unit)
                hold.await()
            }
            return emptyList()
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

    private class CacheThenHoldProfileStore(
        private val worktrees: List<String>,
        private val hold: CompletableDeferred<Unit>,
        private val stallNext: AtomicInteger,
    ) : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            this.profile = profile
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> {
            if (stallNext.get() > 0) {
                hold.await()
            }
            return worktrees
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

    private class PerUrlHoldingProfileStore(
        private val worktreesByUrl: Map<String, List<String>>,
        private val hold: CompletableDeferred<Unit>,
        private val stallNext: AtomicInteger,
        private val stallUrlContains: String,
    ) : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            this.profile = profile
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> {
            if (stallNext.get() > 0 && serverBaseUrl.contains(stallUrlContains)) {
                hold.await()
            }
            return worktreesByUrl.entries
                .firstOrNull { serverBaseUrl.startsWith(it.key.trimEnd('/')) || serverBaseUrl == it.key }
                ?.value
                .orEmpty()
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

    private class MutableWorktreeTestStore(
        private var worktrees: List<String>,
    ) : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null

        fun setWorktrees(directories: List<String>) {
            worktrees = directories
        }

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            this.profile = profile
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = worktrees

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
        ) {
            this.worktrees = worktrees
        }

        override suspend fun clearAll() {
            profile = null
            worktrees = emptyList()
        }

        override fun configurationRevisions(): kotlinx.coroutines.flow.Flow<Unit> =
            kotlinx.coroutines.flow.emptyFlow()
    }

    private class ReplayZeroRevisionStore(
        first: List<String>,
        next: List<String>,
    ) : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null
        private var worktrees = next
        private val firstWorktrees = first
        private val resolves = AtomicInteger(0)
        private val revisions = Channel<Unit>(capacity = Channel.RENDEZVOUS)
        private val firstResolveHold = CompletableDeferred<Unit>()
        val firstResolveEntered = CompletableDeferred<Unit>()

        fun setWorktrees(directories: List<String>) {
            worktrees = directories
        }

        fun emitRevision(): Boolean = revisions.trySend(Unit).isSuccess

        fun releaseFirstResolve() {
            firstResolveHold.complete(Unit)
        }

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            this.profile = profile
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> {
            val n = resolves.incrementAndGet()
            if (n == 1) {
                firstResolveEntered.complete(Unit)
                firstResolveHold.await()
                return firstWorktrees
            }
            return worktrees
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

        override suspend fun clearAll() {
            profile = null
        }

        override fun configurationRevisions(): kotlinx.coroutines.flow.Flow<Unit> =
            flow {
                for (unit in revisions) {
                    emit(unit)
                }
            }
    }

    private class HeldRevisionProfileStore(
        initial: List<String>,
    ) : ConnectionProfileStore {
        private var profile: ConnectionProfile? = null
        private var worktrees = initial
        private val revisions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private val hold = CompletableDeferred<Unit>()
        private val holdingRevision = AtomicBoolean(false)
        private val resolves = AtomicInteger(0)
        val revisionResolveEntered = CompletableDeferred<Unit>()
        var holdNextAddedWorktrees = false

        fun resolveCount(): Int = resolves.get()

        fun setWorktrees(directories: List<String>) {
            worktrees = directories
        }

        fun emitRevision() {
            revisions.tryEmit(Unit)
        }

        fun releaseRevisionResolve() {
            hold.complete(Unit)
        }

        override suspend fun activeProfile(): ConnectionProfile? = profile

        override suspend fun saveProfile(profile: ConnectionProfile) {
            this.profile = profile
        }

        override suspend fun addedWorktrees(serverBaseUrl: String): List<String> {
            resolves.incrementAndGet()
            if (holdNextAddedWorktrees && holdingRevision.compareAndSet(false, true)) {
                holdNextAddedWorktrees = false
                revisionResolveEntered.complete(Unit)
                hold.await()
            }
            return worktrees
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
        ) {
            this.worktrees = worktrees
        }

        override suspend fun clearAll() {
            profile = null
            worktrees = emptyList()
        }

        override fun configurationRevisions(): kotlinx.coroutines.flow.Flow<Unit> = revisions.asSharedFlow()
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
