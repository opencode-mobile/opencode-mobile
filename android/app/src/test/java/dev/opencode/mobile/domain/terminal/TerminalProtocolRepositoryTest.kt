package dev.opencode.mobile.domain.terminal

import dev.opencode.mobile.data.remote.PtyApi
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalProtocolRepositoryTest {
    @Test
    fun v1List_usesDirectoryQueryAndMapsSession() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(ptyArrayJson("pty_1", "running"))
                }
            val repository = repository(client, ServerProtocol.V1)

            val sessions = repository.list("/repo")

            assertEquals("pty_1", sessions.single().id)
            assertEquals("term", sessions.single().title)
            assertEquals("/bin/zsh", sessions.single().command)
            assertEquals("/repo", sessions.single().cwd)
            assertEquals(TerminalSessionStatus.Running, sessions.single().status)
            assertEquals("/repo", requests.single().url.parameters["directory"])
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/pty"),
            )
        }

    @Test
    fun v2List_unwrapsEnvelopeAndUsesLocationDirectory() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """{"data":[${ptyObjectJson("pty_2", "exited")}],"cursor":{"previous":null,"next":null}}""",
                    )
                }
            val repository = repository(client, ServerProtocol.V2)

            val sessions = repository.list("/repo")

            assertEquals("pty_2", sessions.single().id)
            assertEquals(TerminalSessionStatus.Exited, sessions.single().status)
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains("/api/pty"),
            )
        }

    @Test
    fun v2List_acceptsRawArray() =
        runTest {
            val client =
                testClient {
                    respondJson(ptyArrayJson("pty_raw", "running"))
                }
            val repository = repository(client, ServerProtocol.V2)

            val sessions = repository.list("/repo")

            assertEquals("pty_raw", sessions.single().id)
            assertEquals(TerminalSessionStatus.Running, sessions.single().status)
        }

    @Test
    fun v1Create_postsCwdOnlyWithDirectoryQuery() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(ptyObjectJson("pty_new", "running"))
                }
            val repository = repository(client, ServerProtocol.V1)

            val session = repository.create("/repo")

            assertEquals("pty_new", session.id)
            assertEquals(HttpMethod.Post, requests.single().method)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/pty"),
            )
            assertEquals("/repo", requests.single().url.parameters["directory"])
            val body = requests.single().requestBodyText()
            assertTrue(body.contains("\"cwd\":\"/repo\""))
            assertFalse(body.contains("\"command\""))
            assertFalse(body.contains("\"title\""))
        }

    @Test
    fun v2Create_postsCwdWithLocationDirectory() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("""{"data":${ptyObjectJson("pty_v2", "running")}}""")
                }
            val repository = repository(client, ServerProtocol.V2)

            val session = repository.create("/work", cwd = "/work")

            assertEquals("pty_v2", session.id)
            assertEquals(HttpMethod.Post, requests.single().method)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains("/api/pty"),
            )
            assertEquals("/work", requests.single().url.parameters["location[directory]"])
            assertTrue(requests.single().requestBodyText().contains("\"cwd\":\"/work\""))
        }

    @Test
    fun v1Get_usesDirectoryQuery() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(ptyObjectJson("pty_1", "running"))
                }
            val repository = repository(client, ServerProtocol.V1)

            val session = repository.get("pty_1", "/repo")

            assertEquals("pty_1", session.id)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/pty/pty_1"),
            )
            assertEquals("/repo", requests.single().url.parameters["directory"])
        }

    @Test
    fun v2Get_unwrapsDataEnvelope() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("""{"data":${ptyObjectJson("pty_9", "exited")}}""")
                }
            val repository = repository(client, ServerProtocol.V2)

            val session = repository.get("pty_9", "/repo")

            assertEquals("pty_9", session.id)
            assertEquals(TerminalSessionStatus.Exited, session.status)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains("/api/pty/pty_9"),
            )
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
        }

    @Test
    fun v1UpdateSize_putsRowsAndCols() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(ptyObjectJson("pty_1", "running"))
                }
            val repository = repository(client, ServerProtocol.V1)

            repository.updateSize("pty_1", "/repo", cols = 80, rows = 24)

            assertEquals(HttpMethod.Put, requests.single().method)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/pty/pty_1"),
            )
            assertEquals("/repo", requests.single().url.parameters["directory"])
            val body = requests.single().requestBodyText()
            assertTrue(body.contains("\"rows\":24"))
            assertTrue(body.contains("\"cols\":80"))
        }

    @Test
    fun v2UpdateSize_putsApiPathWithLocationDirectory() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("""{"data":${ptyObjectJson("pty_1", "running")}}""")
                }
            val repository = repository(client, ServerProtocol.V2)

            repository.updateSize("pty_1", "/repo", cols = 120, rows = 40)

            assertEquals(HttpMethod.Put, requests.single().method)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains("/api/pty/pty_1"),
            )
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
            val body = requests.single().requestBodyText()
            assertTrue(body.contains("\"rows\":40"))
            assertTrue(body.contains("\"cols\":120"))
        }

    @Test
    fun ensureRunning_reusesFirstRunningSession() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """[${ptyObjectJson("pty_dead", "exited")},${ptyObjectJson("pty_live", "running")}]""",
                    )
                }
            val repository = repository(client, ServerProtocol.V1)

            val session = repository.ensureRunning("/repo")

            assertEquals("pty_live", session.id)
            assertEquals(1, requests.size)
            assertEquals(HttpMethod.Get, requests.single().method)
        }

    @Test
    fun v1Remove_deletesWithDirectoryQuery() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("true")
                }
            val repository = repository(client, ServerProtocol.V1)

            repository.remove("pty_1", "/repo")

            assertEquals(HttpMethod.Delete, requests.single().method)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/pty/pty_1"),
            )
            assertEquals("/repo", requests.single().url.parameters["directory"])
        }

    @Test
    fun v2Remove_deletesApiPathWithLocationDirectory() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("true")
                }
            val repository = repository(client, ServerProtocol.V2)

            repository.remove("pty_9", "/work")

            assertEquals(HttpMethod.Delete, requests.single().method)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains("/api/pty/pty_9"),
            )
            assertEquals("/work", requests.single().url.parameters["location[directory]"])
        }

    @Test
    fun listRunning_fansOutPerDirectoryAndKeepsRunningOnly() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    when (request.url.parameters["directory"]) {
                        "/repo" ->
                            respondJson(
                                """[${ptyObjectJson("pty_dead", "exited")},${ptyObjectJson("pty_live", "running")}]""",
                            )
                        "/play" -> respondJson(ptyArrayJson("pty_play", "running"))
                        else -> respondJson("[]")
                    }
                }
            val repository = repository(client, ServerProtocol.V1)

            val running = repository.listRunning(listOf("/repo", "/play"))

            assertEquals(listOf("pty_live", "pty_play"), running.map { it.session.id })
            assertEquals(listOf("/repo", "/play"), running.map { it.directory })
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.method == HttpMethod.Get })
            assertEquals(null, requests[0].url.parameters["directory"])
            assertEquals("/repo", requests[1].url.parameters["directory"])
            assertEquals("/play", requests[2].url.parameters["directory"])
        }

    @Test
    fun listRunning_unscopedListReturnsRunningWithoutFanOut() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(ptyArrayJson("pty_live", "running"))
                }
            val repository = repository(client, ServerProtocol.V1)

            val running = repository.listRunning(listOf("/repo", "/play"))

            assertEquals(listOf("pty_live"), running.map { it.session.id })
            assertEquals(listOf("/repo"), running.map { it.directory })
            assertEquals(1, requests.size)
            assertEquals(null, requests.single().url.parameters["directory"])
        }

    @Test
    fun listRunning_skipsFailedDirectoryAndKeepsOthers() =
        runTest {
            val client =
                testClient { request ->
                    when (request.url.parameters["directory"]) {
                        "/bad" ->
                            respond(
                                content = "no",
                                status = HttpStatusCode.InternalServerError,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        "/play" -> respondJson(ptyArrayJson("pty_play", "running"))
                        else -> respondJson("[]")
                    }
                }
            val repository = repository(client, ServerProtocol.V1)

            val running = repository.listRunning(listOf("/bad", "/play"))

            assertEquals(listOf("pty_play"), running.map { it.session.id })
            assertEquals(listOf("/play"), running.map { it.directory })
        }

    @Test
    fun list_blankStatusMapsToRunning() =
        runTest {
            val client =
                testClient {
                    respondJson(
                        """[{"id":"pty_1","title":"term","command":"/bin/zsh","cwd":"/repo"}]""",
                    )
                }
            val repository = repository(client, ServerProtocol.V1)

            val sessions = repository.list("/repo")

            assertEquals(TerminalSessionStatus.Running, sessions.single().status)
        }

    @Test
    fun ensureRunning_createsWhenNoneRunning() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    if (request.method == HttpMethod.Get) {
                        respondJson("""[${ptyObjectJson("pty_dead", "exited")}]""")
                    } else {
                        respondJson(ptyObjectJson("pty_created", "running"))
                    }
                }
            val repository = repository(client, ServerProtocol.V1)

            val session = repository.ensureRunning("/repo")

            assertEquals("pty_created", session.id)
            assertEquals(2, requests.size)
            assertEquals(HttpMethod.Get, requests[0].method)
            assertEquals(HttpMethod.Post, requests[1].method)
            val body = requests[1].requestBodyText()
            assertTrue(body.contains("\"cwd\":\"/repo\""))
            assertFalse(body.contains("\"command\""))
        }

    private suspend fun repository(
        client: HttpClient,
        protocol: ServerProtocol,
    ): DefaultTerminalRepository =
        DefaultTerminalRepository(
            PtyApi(client, json),
            TerminalDomainAdapter(),
            activeConnectionManager = manager(protocol),
        )

    private suspend fun manager(protocol: ServerProtocol): ActiveConnectionManager {
        val manager = ActiveConnectionManager(FakeProfileStore())
        manager.applyProfile(
            ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = ""),
            serverProtocol = protocol,
        )
        return manager
    }

    private fun testClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
        }

    private val json = Json { ignoreUnknownKeys = true }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(content: String) =
    respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

private fun HttpRequestData.requestBodyText(): String =
    (body as? io.ktor.http.content.TextContent)?.text
        ?: body.toString()

private fun ptyArrayJson(
    id: String,
    status: String,
): String = "[${ptyObjectJson(id, status)}]"

private fun ptyObjectJson(
    id: String,
    status: String,
): String = """{"id":"$id","title":"term","command":"/bin/zsh","args":[],"cwd":"/repo","status":"$status","pid":12}"""

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
