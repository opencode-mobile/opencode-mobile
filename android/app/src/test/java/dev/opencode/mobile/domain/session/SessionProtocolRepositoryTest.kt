package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.HealthApi
import dev.opencode.mobile.data.remote.ProjectsApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.DefaultProjectRepository
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProtocolRepositoryTest {
    @Test
    fun v1ListSessions_usesFlatArrayAndDirectoryQuery() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """[{"id":"ses_1","title":"One","projectID":"proj","directory":"/repo","time":{"created":1,"updated":2}}]""",
                    )
                }
            val manager = manager(ServerProtocol.V1)
            val repository = DefaultSessionRepository(SessionsApi(client, json), SessionDomainAdapter(), activeConnectionManager = manager)

            val sessions = repository.listSessions("/repo")

            assertEquals("ses_1", sessions.single().id)
            assertEquals("/repo", requests.single().url.parameters["directory"])
            assertTrue(requests.single().url.encodedPath.endsWith("/session"))
        }

    @Test
    fun v2ListSessions_unwrapsEnvelopeAndKeepsDirectoryQuery() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "data":[{
                            "id":"ses_2",
                            "title":"Two",
                            "projectID":"proj",
                            "cost":0,
                            "tokens":{"input":1,"output":2,"reasoning":0,"cache":{"read":0,"write":0}},
                            "time":{"created":1,"updated":2},
                            "location":{"directory":"/repo"}
                          }],
                          "cursor":{"previous":null,"next":null}
                        }
                        """.trimIndent(),
                    )
                }
            val manager = manager(ServerProtocol.V2)
            val repository = DefaultSessionRepository(SessionsApi(client, json), SessionDomainAdapter(), activeConnectionManager = manager)

            val sessions = repository.listSessions("/repo")

            assertEquals("ses_2", sessions.single().id)
            assertEquals("/repo", sessions.single().directory)
            assertEquals("/repo", requests.single().url.parameters["directory"])
            assertTrue(requests.single().url.encodedPath.contains("/api/session"))
        }

    @Test
    fun v2ActiveStatuses_mapRunningToBusy() =
        runTest {
            val client =
                testClient {
                    respondJson("""{"data":{"ses_busy":{"type":"running"}},"watermarks":{}}""")
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val statuses = repository.getAllSessionStatuses("/repo")

            assertEquals("busy", statuses.single().raw["type"])
            assertEquals("ses_busy", statuses.single().sessionId)
        }

    @Test
    fun v2Rename_postsRenameThenReloadsSession() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val responses =
                ArrayDeque(
                    listOf(
                        HttpStatusCode.NoContent to "",
                        HttpStatusCode.OK to
                            """{"data":{"id":"ses_1","title":"Renamed","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}}""",
                    ),
                )
            val client =
                testClient { request ->
                    requests += request
                    val (status, body) = responses.removeFirst()
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val session = repository.updateSession("ses_1", title = "Renamed", directory = "/repo")

            assertEquals("Renamed", session.title)
            assertEquals(HttpMethod.Post, requests[0].method)
            assertTrue(requests[0].url.encodedPath.endsWith("/api/session/ses_1/rename"))
            assertTrue(requests[1].url.encodedPath.endsWith("/api/session/ses_1"))
        }

    @Test
    fun v2Fork_postsMessageIdEnvelope() =
        runTest {
            var captured: HttpRequestData? = null
            val client =
                testClient { request ->
                    captured = request
                    respondJson(
                        """{"data":{"id":"ses_fork","title":"Fork","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"},"parentID":"ses_1"}}""",
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val forked = repository.forkSession("ses_1", atMessageId = "msg_1", directory = "/repo")

            assertEquals("ses_fork", forked.id)
            assertEquals("ses_1", forked.parentId)
            assertEquals(HttpMethod.Post, captured?.method)
            assertTrue(captured!!.url.encodedPath.endsWith("/api/session/ses_1/fork"))
            assertTrue(captured.requestBodyText().contains("\"messageID\":\"msg_1\""))
        }

    @Test
    fun v2ProjectCurrent_usesLocationDirectoryQuery() =
        runTest {
            var captured: HttpRequestData? = null
            val client =
                testClient { request ->
                    captured = request
                    respondJson("""{"id":"proj_1","directory":"/repo"}""")
                }
            val manager = manager(ServerProtocol.V2)
            val repository =
                DefaultProjectRepository(
                    healthApi = HealthApi(OpencodeHttpClient(json, manager)),
                    projectsApi = ProjectsApi(client),
                    adapter = ProjectDomainAdapter(),
                    activeConnectionManager = manager,
                )

            val project = repository.getCurrentProject("/repo")

            assertEquals("proj_1", project.id)
            assertEquals("/repo", project.worktree)
            assertEquals("/repo", captured?.url?.parameters?.get("location[directory]"))
            assertNull(captured?.url?.parameters?.get("directory"))
            assertTrue(captured!!.url.encodedPath.contains("/api/project/current"))
        }

    @Test
    fun v2CreateSession_postsLocationThenOptionalRename() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val responses =
                ArrayDeque(
                    listOf(
                        """{"data":{"id":"ses_new","title":"New session","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}}""",
                        "",
                    ),
                )
            val statuses = ArrayDeque(listOf(HttpStatusCode.OK, HttpStatusCode.NoContent))
            val client =
                testClient { request ->
                    requests += request
                    respond(
                        content = responses.removeFirst(),
                        status = statuses.removeFirst(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val created = repository.createSession(parentId = null, title = "Named", directory = "/repo")

            assertEquals("ses_new", created.id)
            assertEquals("Named", created.title)
            assertTrue(requests[0].url.encodedPath.endsWith("/api/session"))
            assertTrue(requests[0].requestBodyText().contains("\"directory\":\"/repo\""))
            assertTrue(requests[1].url.encodedPath.endsWith("/api/session/ses_new/rename"))
        }

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
