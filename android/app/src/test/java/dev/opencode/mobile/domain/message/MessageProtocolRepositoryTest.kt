package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageProtocolRepositoryTest {
    @Test
    fun v1ListMessages_usesFlatWithPartsArray() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """[{"info":{"id":"msg_1","role":"user"},"parts":[{"type":"text","text":"Hi"}]}]""",
                    )
                }
            val router = router(client, ServerProtocol.V1)

            val messages = router.listMessages("ses_1", "/repo")

            assertEquals("msg_1", messages.single().id)
            assertEquals("Hi", messages.single().text)
            assertEquals("/repo", requests.single().url.parameters["directory"])
            assertTrue(requests.single().url.encodedPath.endsWith("/session/ses_1/message"))
        }

    @Test
    fun v2ListMessages_unwrapsEnvelopeAndProjectsDomainMessages() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "data":[
                            {"id":"msg_user","type":"user","text":"Hello","time":{"created":1}},
                            {
                              "id":"msg_asst",
                              "type":"assistant",
                              "agent":"build",
                              "model":{"id":"gpt-5","providerID":"openai"},
                              "content":[{"type":"text","id":"t1","text":"Hi"}],
                              "time":{"created":2}
                            }
                          ],
                          "cursor":{"previous":null,"next":null}
                        }
                        """.trimIndent(),
                    )
                }
            val router = router(client, ServerProtocol.V2)

            val messages = router.listMessages("ses_2", "/repo")

            assertEquals(listOf("msg_user", "msg_asst"), messages.map { it.id })
            assertEquals("Hello", messages[0].text)
            assertEquals("Hi", messages[1].text)
            assertEquals("/repo", requests.single().url.parameters["directory"])
            assertEquals("asc", requests.single().url.parameters["order"])
            assertTrue(requests.single().url.encodedPath.contains("/api/session/ses_2/message"))
        }

    @Test
    fun v2ListMessages_followsCursorPages() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    val cursor = request.url.parameters["cursor"]
                    if (cursor == null) {
                        respondJson(
                            """{"data":[{"id":"msg_a","type":"user","text":"A","time":{"created":1}}],"cursor":{"next":"page-2"}}""",
                        )
                    } else {
                        respondJson(
                            """{"data":[{"id":"msg_b","type":"user","text":"B","time":{"created":2}}],"cursor":{"next":null}}""",
                        )
                    }
                }
            val router = router(client, ServerProtocol.V2)

            val messages = router.listMessages("ses_2", "/repo")

            assertEquals(listOf("msg_a", "msg_b"), messages.map { it.id })
            assertEquals(2, requests.size)
            assertEquals("page-2", requests[1].url.parameters["cursor"])
        }

    @Test
    fun unknownProtocol_probesBeforeRoutingToV2() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """{"data":[{"id":"msg_v2","type":"user","text":"V2","time":{"created":1}}],"cursor":{"next":null}}""",
                    )
                }
            val manager = manager(null)
            val router =
                MessageProtocolRouter(
                    api = MessagesApi(client, json),
                    adapter = MessageDomainAdapter(),
                    activeConnectionManager = manager,
                    projectRepository = ProbeToV2ProjectRepository(manager),
                )

            val messages = router.listMessages("ses_2", "/repo")

            assertEquals("msg_v2", messages.single().id)
            assertTrue(requests.single().url.encodedPath.contains("/api/session/ses_2/message"))
        }

    private suspend fun router(
        client: HttpClient,
        protocol: ServerProtocol,
    ): MessageProtocolRouter =
        MessageProtocolRouter(
            api = MessagesApi(client, json),
            adapter = MessageDomainAdapter(),
            activeConnectionManager = manager(protocol),
        )

    private suspend fun manager(protocol: ServerProtocol?): ActiveConnectionManager {
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

private fun MockRequestHandleScope.respondJson(content: String) =
    respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

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

private class ProbeToV2ProjectRepository(
    private val manager: ActiveConnectionManager,
) : ProjectRepository {
    override suspend fun probeServer() {
        manager.updateProtocol(ServerProtocol.V2)
    }

    override suspend fun listProjects(): List<Project> = emptyList()

    override suspend fun getCurrentProject(directory: String?): Project = error("unused")
}
