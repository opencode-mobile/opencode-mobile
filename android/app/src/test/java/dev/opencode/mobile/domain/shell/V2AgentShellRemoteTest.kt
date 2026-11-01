package dev.opencode.mobile.domain.shell

import dev.opencode.mobile.data.remote.ShellApi
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class V2AgentShellRemoteTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventAdapter = EventDomainAdapter(json)
    private val session = AgentShellSession(sessionId = "ses_test", directory = "/tmp/project", messages = emptyFlow())

    @Test
    fun listKeepsRunningShellsOfTheOpenSession() =
        runTest { scope ->
            val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
            val stream = FakeEventStream()
            val remote =
                remote(stream) { request ->
                    requests += request
                    respondJson(
                        AgentShellFixtures.listBody(
                            AgentShellFixtures.shellInfo(),
                            AgentShellFixtures.shellInfo(id = "sh_other", sessionId = "ses_other"),
                        ),
                    )
                }
            val latest = collectShells(scope, remote)

            val shells = latest.first { it.isNotEmpty() }

            assertEquals(listOf(AgentShellFixtures.RUNNING_ID), shells.map { it.id })
            assertTrue(shells.single().canStop)
            val list = requests.first()
            assertEquals(HttpMethod.Get, list.method)
            assertTrue(list.url.encodedPath.endsWith("/api/shell"))
            assertEquals("/tmp/project", list.url.parameters["location[directory]"])
        }

    @Test
    fun createdExitedAndDeletedEventsUpdateTheList() =
        runTest { scope ->
            val stream = FakeEventStream()
            val remote = remote(stream) { respondJson(AgentShellFixtures.listBody()) }
            val latest = collectShells(scope, remote)
            stream.awaitSubscriber()

            stream.send(AgentShellFixtures.createdEvent(AgentShellFixtures.shellInfo(id = "sh_other", sessionId = "ses_other")))
            stream.send(AgentShellFixtures.createdEvent())
            val created = latest.first { it.isNotEmpty() }
            assertEquals(listOf(AgentShellFixtures.RUNNING_ID), created.map { it.id })

            stream.send(AgentShellFixtures.KILLED_EVENT)
            val killed = latest.first { list -> list.singleOrNull()?.isRunning == false }.single()
            assertEquals(AgentShellStatus.Stopped, killed.status)
            assertEquals(1_793_548_743_748L, killed.finishedAtMs)
            assertFalse(killed.canStop)

            stream.send(AgentShellFixtures.DELETED_EVENT)
            assertEquals(emptyList<AgentShell>(), latest.first { it.isEmpty() })
        }

    @Test
    fun exitedEventKeepsTheExitCode() {
        val adapter = AgentShellDomainAdapter(json)
        val info =
            json.decodeFromString(
                dev.opencode.mobile.data.remote.dto.ShellInfoDto
                    .serializer(),
                AgentShellFixtures.shellInfo(id = AgentShellFixtures.EXITED_ID),
            )
        val list =
            V2ShellList()
                .apply(AgentShellEvent.Created(info), "ses_test", adapter)
                .apply(adapter.shellEvent(eventAdapter.parseEvent(AgentShellFixtures.EXITED_WITH_CODE_EVENT)!!)!!, "ses_test", adapter)

        assertEquals(AgentShellStatus.Exited(3), list.shells.single().status)
    }

    @Test
    fun reconnectReloadsTheList() =
        runTest { scope ->
            val stream = FakeEventStream(EventStreamConnectionState.Connected)
            var listed = 0
            val remote =
                remote(stream) {
                    listed++
                    if (listed == 1) {
                        respondJson(AgentShellFixtures.listBody(AgentShellFixtures.shellInfo()))
                    } else {
                        respondJson(AgentShellFixtures.listBody())
                    }
                }
            val latest = collectShells(scope, remote)
            latest.first { it.isNotEmpty() }

            stream.connection.value = EventStreamConnectionState.Reconnecting
            kotlinx.coroutines.delay(50)
            stream.connection.value = EventStreamConnectionState.Connected

            // The shell ended while the stream was down: the new list drops it.
            assertEquals(emptyList<AgentShell>(), latest.first { it.isEmpty() })
            assertEquals(2, listed)
        }

    @Test
    fun unsupportedServerHasNoShellsAndSendsNoRequests() =
        runTest {
            var requests = 0
            val remote =
                remote(FakeEventStream(), supported = false) {
                    requests++
                    respondJson(AgentShellFixtures.listBody(AgentShellFixtures.shellInfo()))
                }

            assertEquals(emptyList<AgentShell>(), remote.shells(session).first())
            assertEquals(0, requests)
        }

    @Test
    fun supportIsReadAgainOnReconnect() =
        runTest { scope ->
            // Cold start: Session Chat opens before the server's API contract is probed.
            var supported = false
            val stream = FakeEventStream(EventStreamConnectionState.Connecting)
            var requests = 0
            val remote =
                remote(stream, isSupported = { supported }) {
                    requests++
                    respondJson(AgentShellFixtures.listBody(AgentShellFixtures.shellInfo()))
                }
            val latest = collectShells(scope, remote)
            kotlinx.coroutines.delay(50)
            assertEquals(emptyList<AgentShell>(), latest.value)
            assertEquals(0, requests)

            supported = true
            stream.connection.value = EventStreamConnectionState.Connected

            assertEquals(listOf(AgentShellFixtures.RUNNING_ID), latest.first { it.isNotEmpty() }.map { it.id })
        }

    @Test
    fun aShellThatEndedWhileDisconnectedLeavesOnTheReconnectList() =
        runTest { scope ->
            val stream = FakeEventStream(EventStreamConnectionState.Connected)
            var listed = 0
            val remote =
                remote(stream) {
                    listed++
                    respondJson(AgentShellFixtures.listBody())
                }
            val latest = collectShells(scope, remote)
            stream.awaitSubscriber()
            while (listed == 0) kotlinx.coroutines.delay(5)
            kotlinx.coroutines.delay(50)

            // Created after the first list, then exited and deleted while the stream was down.
            stream.send(AgentShellFixtures.createdEvent())
            latest.first { it.isNotEmpty() }
            stream.connection.value = EventStreamConnectionState.Reconnecting
            kotlinx.coroutines.delay(50)
            stream.connection.value = EventStreamConnectionState.Connected

            assertEquals(emptyList<AgentShell>(), latest.first { it.isEmpty() })
            assertEquals(2, listed)
        }

    @Test
    fun aShellCreatedWhileTheListLoadsSurvivesTheAnswer() {
        val adapter = AgentShellDomainAdapter(json)
        val info =
            json.decodeFromString(
                dev.opencode.mobile.data.remote.dto.ShellInfoDto
                    .serializer(),
                AgentShellFixtures.shellInfo(),
            )
        val created = AgentShellEvent.Created(info)

        val inFlight = V2ShellList().startRefresh().apply(created, "ses_test", adapter).replaceRunning(emptyList())
        val afterList = V2ShellList().startRefresh().replaceRunning(emptyList()).apply(created, "ses_test", adapter)

        assertEquals(listOf(AgentShellFixtures.RUNNING_ID), inFlight.shells.map { it.id })
        // Created after the list answered: the next list is the authority.
        assertEquals(emptyList<AgentShell>(), afterList.startRefresh().replaceRunning(emptyList()).shells)
    }

    @Test
    fun outputKeepsPagesReadBeforeALaterPageFails() =
        runTest {
            val remote =
                remote(FakeEventStream()) { request ->
                    when (request.url.parameters["cursor"]) {
                        V2ShellOutputReader.PROBE_CURSOR.toString() -> respondJson(AgentShellFixtures.outputBody("", 20, 20))
                        "0" -> respondJson(AgentShellFixtures.outputBody("page 1\n", 10, 20))
                        "10" -> respond("", HttpStatusCode.InternalServerError)
                        else -> respondJson(AgentShellFixtures.outputBody("", 20, 20))
                    }
                }

            assertEquals("page 1\n", remote.output(running(), session).first().text)
        }

    @Test
    fun outputReadsTheWholeShortOutput() =
        runTest {
            val cursors = Collections.synchronizedList(mutableListOf<String?>())
            val remote =
                remote(FakeEventStream()) { request ->
                    val cursor = request.url.parameters["cursor"]
                    cursors += cursor
                    when (cursor) {
                        V2ShellOutputReader.PROBE_CURSOR.toString() -> respondJson(AgentShellFixtures.outputBody("", 35, 35))
                        else -> respondJson(AgentShellFixtures.outputBody("line 1\nline 2\nline 3\nline 4\nline 5\n", 35, 35))
                    }
                }

            val output = remote.output(running(), session).first()

            assertEquals(AgentShellOutput("line 1\nline 2\nline 3\nline 4\nline 5\n", isTruncated = false), output)
            assertEquals(listOf(V2ShellOutputReader.PROBE_CURSOR.toString(), "0"), cursors.take(2))
        }

    @Test
    fun outputStartsAtTheTailAndDropsThePartialLine() =
        runTest {
            val cursors = Collections.synchronizedList(mutableListOf<String?>())
            val size = 1_988_895L
            val start = size - V2ShellOutputReader.TAIL_BYTES
            val remote =
                remote(FakeEventStream()) { request ->
                    val cursor = request.url.parameters["cursor"]
                    cursors += cursor
                    when (cursor) {
                        V2ShellOutputReader.PROBE_CURSOR.toString() -> respondJson(AgentShellFixtures.outputBody("", size, size))
                        start.toString() -> respondJson(AgentShellFixtures.outputBody("ne 9\nline 10\n", size, size))
                        else -> respondJson(AgentShellFixtures.outputBody("", size, size))
                    }
                }

            val output = remote.output(running(), session).first()

            assertEquals(AgentShellOutput("line 10\n", isTruncated = true), output)
            assertEquals(listOf(V2ShellOutputReader.PROBE_CURSOR.toString(), start.toString()), cursors.take(2))
        }

    @Test
    fun outputKeepsReadingPagesWhileBehind() =
        runTest {
            val remote =
                remote(FakeEventStream()) { request ->
                    when (request.url.parameters["cursor"]) {
                        V2ShellOutputReader.PROBE_CURSOR.toString() -> respondJson(AgentShellFixtures.outputBody("", 20, 20))
                        "0" -> respondJson(AgentShellFixtures.outputBody("page 1\n", 10, 20))
                        "10" -> respondJson(AgentShellFixtures.outputBody("page 2\n", 20, 20))
                        else -> respondJson(AgentShellFixtures.outputBody("", 20, 20))
                    }
                }

            assertEquals("page 1\npage 2\n", remote.output(running(), session).first().text)
        }

    @Test
    fun outputPollsByCursorAndStopsWhenTheShellIsRemoved() =
        runTest {
            var reads = 0
            val remote =
                remote(FakeEventStream()) { request ->
                    reads++
                    when (request.url.parameters["cursor"]) {
                        V2ShellOutputReader.PROBE_CURSOR.toString() -> respondJson(AgentShellFixtures.outputBody("", 7, 7))
                        "0" -> respondJson(AgentShellFixtures.outputBody("line 1\n", 7, 7))
                        "7" ->
                            if (reads < 5) {
                                respondJson(AgentShellFixtures.outputBody("line 2\n", 14, 14))
                            } else {
                                respondJson(AgentShellFixtures.outputBody("", 14, 14))
                            }
                        else -> respond(AgentShellFixtures.NOT_FOUND_BODY, HttpStatusCode.NotFound, jsonHeaders)
                    }
                }

            // The flow ends by itself once the server answers 404.
            val outputs = remote.output(running(), session).toList()

            assertEquals(listOf("line 1\n", "line 1\nline 2\n"), outputs.map { it.text })
            val readsAtEnd = reads
            kotlinx.coroutines.delay(20)
            assertEquals(readsAtEnd, reads)
        }

    @Test
    fun endedShellOutputIsReadOnce() =
        runTest {
            var reads = 0
            val remote =
                remote(FakeEventStream()) { request ->
                    reads++
                    when (request.url.parameters["cursor"]) {
                        V2ShellOutputReader.PROBE_CURSOR.toString() -> respondJson(AgentShellFixtures.outputBody("", 5, 5))
                        else -> respondJson(AgentShellFixtures.outputBody("done\n", 5, 5))
                    }
                }

            val outputs = remote.output(running().copy(status = AgentShellStatus.Exited(0)), session).toList()

            assertEquals(listOf("done\n"), outputs.map { it.text })
            assertEquals(2, reads)
        }

    @Test
    fun stopDeletesTheShellInItsLocation() =
        runTest {
            val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
            val remote =
                remote(FakeEventStream()) { request ->
                    requests += request
                    respond("", HttpStatusCode.NoContent)
                }

            remote.stop(running(), session)

            val delete = requests.single()
            assertEquals(HttpMethod.Delete, delete.method)
            assertTrue(delete.url.encodedPath.endsWith("/api/shell/${AgentShellFixtures.RUNNING_ID}"))
            assertEquals("/tmp/project", delete.url.parameters["location[directory]"])
        }

    @Test(expected = Exception::class)
    fun stopThrowsWhenTheServerRefuses() =
        runTest {
            remote(FakeEventStream()) { respond("", HttpStatusCode.InternalServerError) }.stop(running(), session)
        }

    private fun running() =
        AgentShell(
            id = AgentShellFixtures.RUNNING_ID,
            sessionId = "ses_test",
            command = AgentShellFixtures.COMMAND,
            description = null,
            status = AgentShellStatus.Running,
            startedAtMs = 1_793_548_739_990L,
            finishedAtMs = null,
            canStop = true,
        )

    private fun collectShells(
        scope: CoroutineScope,
        remote: V2AgentShellRemote,
    ): MutableStateFlow<List<AgentShell>> {
        val latest = MutableStateFlow<List<AgentShell>>(emptyList())
        scope.launch { remote.shells(session).collect { latest.value = it } }
        return latest
    }

    private fun remote(
        stream: FakeEventStream,
        supported: Boolean = true,
        isSupported: () -> Boolean = { supported },
        handler: MockRequestHandler,
    ) = V2AgentShellRemote(
        api = ShellApi(client(handler), json),
        adapter = AgentShellDomainAdapter(json),
        eventStream = stream,
        supported = isSupported,
        pollIntervalMs = 1,
    )

    private fun client(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
        }

    private fun runTest(block: suspend (CoroutineScope) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            try {
                withTimeout(5_000) { block(scope) }
            } finally {
                scope.cancel()
            }
        }

    private inner class FakeEventStream(
        initial: EventStreamConnectionState = EventStreamConnectionState.Idle,
    ) : ForegroundEventStream {
        val connection = MutableStateFlow(initial)
        override val connectionState = connection
        override val events = MutableSharedFlow<OpencodeEvent>(extraBufferCapacity = 16)

        override fun start() = Unit

        override suspend fun stop() = Unit

        suspend fun awaitSubscriber() {
            events.subscriptionCount.first { it > 0 }
        }

        suspend fun send(line: String) {
            eventAdapter.parseEvents(line).forEach { events.emit(it) }
        }
    }
}

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private fun MockRequestHandleScope.respondJson(content: String) = respond(content, HttpStatusCode.OK, jsonHeaders)
