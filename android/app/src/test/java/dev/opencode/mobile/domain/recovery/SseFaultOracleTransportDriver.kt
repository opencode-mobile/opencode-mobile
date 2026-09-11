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
                        "target.open" -> {
                            host.awaitOpen(checkNotNull(step.target))
                            host.captureGeneration()
                        }
                        "protocol.set" -> host.setProtocol(SseFaultOracleDriver.protocolOf(checkNotNull(step.state)))
                        "target.activity" -> {
                            val target = checkNotNull(step.target)
                            heartbeatJobs += launch { host.heartbeat(target) }
                        }
                        "target.silence" -> host.silence(checkNotNull(step.target))
                        "target.close" -> host.closeTarget(checkNotNull(step.target))
                        "target.write" ->
                            host.write(
                                checkNotNull(step.target),
                                checkNotNull(step.event) { "target.write needs event" },
                            )
                        "directories.set" -> host.setDirectories(step.directories.orEmpty())
                        "profile.switch" ->
                            host.switchProfile(
                                profileId = checkNotNull(step.profileId) { "profile.switch needs profileId" },
                                directories = step.directories ?: listOfNotNull(step.directory),
                            )
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
        private var protocol = SseFaultOracleDriver.protocolOf(trace.serverProtocol)
        private val directories = CopyOnWriteArrayList(trace.seed.directories.ifEmpty { listOf(trace.seed.directory) })
        private var currentProfile =
            ConnectionProfile(
                baseUrl = baseUrlFor(trace.seed.profileId),
                username = "opencode",
                password = "x",
                id = trace.seed.profileId,
            )
        private var generationAtStart: Long? = null
        val store = MutableWorktreeProfileStore(directories.toList())
        private val channels = ConcurrentHashMap<String, ByteChannel>()
        private val live = ConcurrentHashMap.newKeySet<String>()
        private val opened = CopyOnWriteArrayList<String>()
        private val silenced = ConcurrentHashMap.newKeySet<String>()
        private val openedSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val allowOpen = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val client: HttpClient
        val stream: DefaultForegroundEventStream
        private val manager: ActiveConnectionManager
        private val writerScope = CoroutineScope(Dispatchers.Unconfined + Job())
        private val publishedTypes = CopyOnWriteArrayList<String>()
        private var collectJob: Job? = null

        init {
            client =
                HttpClient(
                    MockEngine { request ->
                        val target = targetKey(request)
                        val path = openedPath(request)
                        allowOpen.getOrPut(target) { CompletableDeferred() }.await()
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
            manager =
                ActiveConnectionManager(
                    store,
                    ConnectionProbing { protocol },
                )
            runBlocking {
                store.saveProfile(currentProfile)
                manager.applyProfile(currentProfile, serverProtocol = protocol)
            }
            stream =
                DefaultForegroundEventStream(
                    eventsApi = EventsApi(client),
                    eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    activeConnectionManager = manager,
                    connectionProfileStore = store,
                    idleTimeoutMs =
                        if (trace.steps.any { it.op == "target.silence" }) 150L else 45_000L,
                )
            collectJob =
                writerScope.launch {
                    stream.events.collect { publishedTypes += it.type }
                }
        }

        suspend fun awaitOpen(target: String) {
            allowOpen.getOrPut(target) { CompletableDeferred() }.complete(Unit)
            withTimeout(2_500) {
                openedSignals.getOrPut(target) { CompletableDeferred() }.await()
                while (target !in stream.snapshot.value.liveTargets) {
                    delay(10)
                }
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
            live.remove(target)
            openedSignals.remove(target)
        }

        fun closeTarget(target: String) {
            live.remove(target)
            openedSignals.remove(target)
            allowOpen[target] = CompletableDeferred()
            channels[target]?.close()
        }

        suspend fun write(
            target: String,
            event: SseFaultEvent,
        ) {
            val sessionId = event.sessionId ?: "ses-1"
            val payload =
                if (event.type == "message.part.delta") {
                    "data: {\"payload\":{\"type\":\"message.part.delta\",\"properties\":{" +
                        "\"sessionID\":\"$sessionId\"," +
                        "\"delta\":\"${event.text ?: ""}\"," +
                        "\"field\":\"${event.field ?: "text"}\"," +
                        "\"messageID\":\"${event.messageId ?: "msg-1"}\"," +
                        "\"partID\":\"${event.partId ?: "part-1"}\"}}}\n\n"
                } else {
                    "data: {\"payload\":{\"type\":\"${event.type}\",\"properties\":{" +
                        "\"sessionID\":\"$sessionId\"}}}\n\n"
                }
            runCatching { channels[target]?.writeStringUtf8(payload) }
        }

        suspend fun setDirectories(next: List<String>) {
            directories.clear()
            directories.addAll(next)
            store.setAddedWorktrees(currentProfile.baseUrl, next)
        }

        suspend fun switchProfile(
            profileId: String,
            directories: List<String>,
        ) {
            val next =
                ConnectionProfile(
                    baseUrl = baseUrlFor(profileId),
                    username = "opencode",
                    password = "x",
                    id = profileId,
                )
            currentProfile = next
            this.directories.clear()
            this.directories.addAll(directories)
            store.saveProfile(next)
            store.setAddedWorktrees(next.baseUrl, directories)
            manager.applyProfile(next, serverProtocol = protocol)
            openedSignals.clear()
            live.clear()
            channels.values.forEach { it.close() }
            channels.clear()
            stream.stop()
            stream.start()
        }

        private fun baseUrlFor(profileId: String): String =
            if (profileId == trace.seed.profileId) {
                MutableWorktreeProfileStore.DEFAULT_SERVER
            } else {
                SWITCHED_SERVER
            }

        fun captureGeneration() {
            if (generationAtStart == null) {
                generationAtStart = stream.snapshot.value.generation
            }
        }

        suspend fun setProtocol(next: ServerProtocol) {
            protocol = next
            manager.applyProfile(currentProfile, serverProtocol = next)
            stream.start()
            withTimeout(2_000) {
                while (stream.snapshot.value.requiredTargets !=
                    SseFaultOracleDriver.requiredTargets(next, directories.toList())
                ) {
                    delay(10)
                }
            }
        }

        suspend fun assertSnapshot(expected: SseFaultSnapshot) {
            expected.publishedEventTypes?.let { wanted ->
                withTimeout(1_000) {
                    while (publishedTypes.toSet() != wanted.toSet()) {
                        delay(10)
                    }
                }
            }
            val snapshot = stream.snapshot.value
            expected.requiredTargets?.let { assertEquals(it, snapshot.requiredTargets) }
            expected.liveTargets?.let { wanted ->
                assertEquals(wanted.toSet(), snapshot.liveTargets.toSet())
            }
            expected.generationUnchanged?.let { unchanged ->
                val baseline = checkNotNull(generationAtStart)
                assertEquals(unchanged, snapshot.generation == baseline)
            }
            expected.gapReason?.let {
                assertEquals(it, SseFaultOracleDriver.gapReasonName(snapshot.gap?.reason))
            }
            expected.openedPaths?.let { wanted ->
                wanted.forEach { path ->
                    assertTrue(
                        "openedPaths missing $path in $opened",
                        opened.any { it == path || it.startsWith("$path?") || path.startsWith("$it?") },
                    )
                }
            }
            expected.openedPathCounts?.let { wanted ->
                wanted.forEach { (path, count) ->
                    val matches =
                        opened.count {
                            it == path || it.startsWith("$path?") || path.startsWith("$it?")
                        }
                    assertEquals("openedPathCounts for $path", count, matches)
                }
            }
            expected.connectionState?.let {
                assertEquals(it, SseFaultOracleDriver.connectionStateName(snapshot.connectionState))
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
            expected.publishedProfileId?.let { wanted ->
                assertEquals(wanted, snapshot.config?.profileId)
            }
            expected.publishedEventTypes?.let { wanted ->
                assertEquals(wanted.toSet(), publishedTypes.toSet())
            }
        }

        fun close() {
            collectJob?.cancel()
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
        private const val SWITCHED_SERVER = "http://10.0.0.2:5096/"
        private const val CONNECTED_FRAME =
            "data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n"
        private const val HEARTBEAT_FRAME =
            "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n"
    }
}
