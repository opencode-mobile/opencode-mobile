package dev.opencode.mobile.domain.command

import dev.opencode.mobile.data.remote.CommandsApi
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
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
import org.junit.Assert.fail
import org.junit.Test

class CommandProtocolRepositoryTest {
    @Test
    fun v1ListCommands_objectTemplateDoesNotFailCatalog() =
        runTest {
            val client =
                testClient {
                    respondJson(
                        """
                        [
                          {"name":"init","description":"Setup","source":"command","template":"do init"},
                          {"name":"firebase:firebase_init","description":"Init Firebase","source":"mcp","template":{}}
                        ]
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultCommandRepository(
                    CommandsApi(client, json),
                    CommandDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V1),
                )

            val commands = repository.listCommands("/repo")

            assertEquals(listOf("init", "firebase:firebase_init"), commands.map { it.name })
            assertEquals("do init", commands[0].template)
            assertNull(commands[1].template)
            assertEquals(ServerCommandSource.Mcp, commands[1].source)
        }

    @Test
    fun v2ListCommands_objectTemplateDoesNotFailCatalog() =
        runTest {
            val client =
                testClient { request ->
                    when {
                        request.url.encodedPath.contains("/api/skill") ->
                            respondJson("""{"data":[]}""")
                        else ->
                            respondJson(
                                """{"data":[{"name":"firebase:firebase_init","description":"Init Firebase","source":"mcp","template":{}}]}""",
                            )
                    }
                }
            val repository =
                DefaultCommandRepository(
                    CommandsApi(client, json),
                    CommandDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val commands = repository.listCommands("/repo")

            assertEquals("firebase:firebase_init", commands.single().name)
            assertNull(commands.single().template)
            assertEquals(ServerCommandSource.Mcp, commands.single().source)
        }

    @Test
    fun v2ListCommands_unwrapsLocationEnvelope() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("""{"data":[{"name":"init","template":"do init","description":"Setup"}]}""")
                }
            val repository =
                DefaultCommandRepository(
                    CommandsApi(client, json),
                    CommandDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val commands = repository.listCommands("/repo")

            assertEquals("init", commands.single().name)
            assertEquals("Setup", commands.single().description)
            assertTrue(requests.any { it.url.encodedPath.contains("/api/command") })
            assertEquals("/repo", requests.single { it.url.encodedPath.contains("/api/command") }.url.parameters["location[directory]"])
            assertTrue(requests.any { it.url.encodedPath.contains("/api/skill") })
        }

    @Test
    fun v2ListCommands_mergesSkillsFromSkillEndpoint() =
        runTest {
            val client =
                testClient { request ->
                    when {
                        request.url.encodedPath.contains("/api/skill") ->
                            respondJson(
                                """{"data":[{"name":"prototype","description":"A skill","location":"/skills/prototype/SKILL.md","content":"Do it"}]}""",
                            )
                        else ->
                            respondJson("""{"data":[{"name":"init","template":"do init","description":"Setup"}]}""")
                    }
                }
            val repository =
                DefaultCommandRepository(
                    CommandsApi(client, json),
                    CommandDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val commands = repository.listCommands("/repo")

            assertEquals(listOf("init", "prototype"), commands.map { it.name })
            assertEquals(ServerCommandSource.Command, commands[0].source)
            assertEquals(ServerCommandSource.Skill, commands[1].source)
        }

    @Test
    fun v2RunCommand_postsApiSessionCommand() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond("", HttpStatusCode.NoContent)
                }
            val repository =
                DefaultCommandRepository(
                    CommandsApi(client, json),
                    CommandDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            repository.runCommand("ses_1", CommandRunOptions(command = "init", arguments = "now"), "/repo")

            assertEquals(HttpMethod.Post, requests.single().method)
            assertTrue(requests.single().url.encodedPath.contains("/api/session/ses_1/command"))
            assertTrue((requests.single().body as io.ktor.http.content.TextContent).text.contains("\"command\":\"init\""))
            assertTrue((requests.single().body as io.ktor.http.content.TextContent).text.contains("\"delivery\":\"steer\""))
            assertTrue(!(requests.single().body as io.ktor.http.content.TextContent).text.contains("\"parts\""))
            assertNull(requests.single().url.parameters["directory"])
            assertNull(requests.single().url.parameters["location[directory]"])
        }

    @Test
    fun v1RunCommand_http400_surfacesResponseBody() =
        runTest {
            val client =
                testClient {
                    respond("parts.0.type Required", HttpStatusCode.BadRequest)
                }
            val repository =
                DefaultCommandRepository(
                    CommandsApi(client, json),
                    CommandDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V1),
                )

            try {
                repository.runCommand("ses_1", CommandRunOptions(command = "init", arguments = "now"), "/repo")
                fail("expected CommandError.Http")
            } catch (error: CommandError.Http) {
                assertEquals(400, error.statusCode)
                assertEquals("parts.0.type Required", error.message)
            }
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
            install(HttpTimeout)
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
