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
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandProtocolRepositoryTest {
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
            assertTrue(requests.single().url.encodedPath.contains("/api/command"))
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
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
