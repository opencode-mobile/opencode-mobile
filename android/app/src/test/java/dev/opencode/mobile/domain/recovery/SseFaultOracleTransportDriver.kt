package dev.opencode.mobile.domain.recovery

import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.event.DefaultForegroundEventStream
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.StreamOccupancy
import dev.opencode.mobile.domain.event.composeLiveUpdates
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SseFaultOracleTransportDriver {
    fun run(trace: SseFaultTrace) =
        runBlocking {
            val host = TransportHost(trace)
            try {
                var lastT = 0L
                val heartbeatJobs = mutableListOf<Job>()
                for (step in trace.steps) {
                    val delta = step.t - lastT
                    if (delta > 0) delay(delta)
                    lastT = step.t
                    when (step.op) {
                        "stream.start" -> host.stream.start()
                        "stream.stop" -> host.stream.stop()
                        "target.open" -> host.awaitOpen(checkNotNull(step.target))
                        "target.activity" -> {
                            val target = checkNotNull(step.target)
                            heartbeatJobs += launch { host.heartbeat(target) }
                        }
                        "target.silence" -> host.silence(checkNotNull(step.target))
                        "directories.set" -> host.setDirectories(step.directories.orEmpty())
                        "snapshot" -> {
                            val name = checkNotNull(step.name)
                            val expected =
                                checkNotNull(trace.expected.snapshots[name]) {
                                    "Missing expected snapshot $name"
                                }
                            host.assertSnapshot(expected)
                        }
                        else -> error("Unsupported transport op ${step.op}")
                    }
                }
                heartbeatJobs.forEach { it.cancel() }
            } finally {
                host.close()
            }
        }

    private class TransportHost(
        private val trace: SseFaultTrace,
    ) {
        private val protocol = SseFaultOracleDriver.protocolOf(trace.serverProtocol)
        private val directories = CopyOnWriteArrayList(trace.seed.directories.ifEmpty { listOf(trace.seed.directory) })
        val store = MutableWorktreeProfileStore(directories.toList())
        private val channels = ConcurrentHashMap<String, ByteChannel>()
        private val live = ConcurrentHashMap.newKeySet<String>()
        private val opened = CopyOnWriteArrayList<String>()
        private val silenced = ConcurrentHashMap.newKeySet<String>()
        private val openedSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val client: HttpClient
        val stream: DefaultForegroundEventStream
        private val writerScope = CoroutineScope(Dispatchers.Unconfined + Job())

        init {
            client =
                HttpClient(
                    MockEngine { request ->
                        val target = targetKey(request)
                        val path = openedPath(request)
                        val channel = ByteChannel(autoFlush = true)
                        channels[target] = channel
                        opened += path
                        live.add(target)
                        openedSignals.getOrPut(target) { CompletableDeferred() }.complete(Unit)
                        channel.writeStringUtf8(CONNECTED_FRAME)
                        respond(
                            content = channel,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            val manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { protocol },
                )
            runBlocking {
                store.saveProfile(sampleProfile)
                manager.applyProfile(sampleProfile, serverProtocol = protocol)
            }
            stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                    idleTimeoutMs = 150,
                )
        }

        suspend fun awaitOpen(target: String) {
            withTimeout(2_000) {
                openedSignals.getOrPut(target) { CompletableDeferred() }.await()
            }
        }

        suspend fun heartbeat(target: String) {
            while (writerScope.isActive && target !in silenced) {
                channels[target]?.writeStringUtf8(HEARTBEAT_FRAME)
                delay(40)
            }
        }

        fun silence(target: String) {
            silenced.add(target)
        }

        suspend fun setDirectories(next: List<String>) {
            directories.clear()
            directories.addAll(next)
            store.setAddedWorktrees(MutableWorktreeProfileStore.DEFAULT_SERVER, next)
        }

        fun assertSnapshot(expected: SseFaultSnapshot) {
            expected.requiredTargets?.let {
                assertEquals(
                    it,
                    SseFaultOracleDriver.requiredTargets(protocol, directories.toList()),
                )
            }
            expected.liveTargets?.let { wanted ->
                val actual =
                    channels
                        .filterValues { !it.isClosedForWrite }
                        .keys
                        .ifEmpty { live }
                assertEquals(wanted.toSet(), actual.toSet())
            }
            expected.openedPaths?.let { wanted ->
                wanted.forEach { path ->
                    assertTrue(
                        "openedPaths missing $path in $opened",
                        opened.any { it == path || it.startsWith("$path?") || path.startsWith("$it?") },
                    )
                }
            }
            expected.connectionState?.let {
                assertEquals(it, SseFaultOracleDriver.connectionStateName(stream.connectionState.value))
            }
            expected.chrome?.let { label ->
                val chrome =
                    composeLiveUpdates(
                        connectionState = stream.connectionState.value,
                        path = DeviceNetworkPathStatus.Ready,
                        occupancy = StreamOccupancy.Active,
                        hasConnection = true,
                    )
                assertEquals(label, chrome.label.label)
            }
        }

        fun close() {
            stream.stop()
            client.close()
        }

        private fun targetKey(request: HttpRequestData): String {
            val path = request.url.encodedPath
            val directory = request.url.parameters["directory"]
            return when {
                path.contains("global/event") -> "global/event"
                path.contains("api/event") -> "api/event"
                directory != null -> "event:$directory"
                else -> "event"
            }
        }

        private fun openedPath(request: HttpRequestData): String {
            val path =
                when {
                    request.url.encodedPath.contains("global/event") -> "/global/event"
                    request.url.encodedPath.contains("api/event") -> "/api/event"
                    else -> "/event"
                }
            val directory = request.url.parameters["directory"]
            return if (directory != null) "$path?directory=$directory" else path
        }
    }

    companion object {
        private val sampleProfile =
            ConnectionProfile(
                baseUrl = MutableWorktreeProfileStore.DEFAULT_SERVER,
                username = "opencode",
                password = "x",
            )
        private const val CONNECTED_FRAME =
            "data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n"
        private const val HEARTBEAT_FRAME =
            "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n"
    }
}
