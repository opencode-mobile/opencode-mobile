package dev.opencode.mobile.domain.mcp

import dev.opencode.mobile.data.remote.McpApi
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolRepositoryTest {
    @Test
    fun v1ListServers_parsesStatusMapAndSortsByName() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "playwright": {"status":"failed","error":"spawn npx ENOENT"},
                          "context7": {"status":"connected"},
                          "github": {"status":"needs_auth"},
                          "notion": {"status":"needs_client_registration"},
                          "filesystem": {"status":"disabled"}
                        }
                        """.trimIndent(),
                    )
                }
            val repository = DefaultMcpRepository(McpApi(client), McpDomainAdapter(), manager(ServerProtocol.V1))

            val servers = repository.listServers("/repo")

            assertEquals(listOf("context7", "filesystem", "github", "notion", "playwright"), servers.map { it.name })
            assertEquals(
                listOf(
                    McpServerStatus.Connected,
                    McpServerStatus.Disabled,
                    McpServerStatus.NeedsSignIn,
                    McpServerStatus.NeedsSignIn,
                    McpServerStatus.Failed,
                ),
                servers.map { it.status },
            )
            assertEquals("spawn npx ENOENT", servers.last().errorMessage)
            assertNull(servers.first().errorMessage)
            assertEquals("/mcp", requests.single().url.encodedPath)
            assertEquals("/repo", requests.single().url.parameters["directory"])
        }

    @Test
    fun v2ListServers_unwrapsEnvelopeAndMapsPending() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {"location":{"directory":"/repo"},"data":[
                          {"name":"linear","status":{"status":"pending"}},
                          {"name":"github","status":{"status":"needs_auth","error":"OAuth token expired"}},
                          {"name":"context7","status":{"status":"connected"}}
                        ]}
                        """.trimIndent(),
                    )
                }
            val repository = DefaultMcpRepository(McpApi(client), McpDomainAdapter(), manager(ServerProtocol.V2))

            val servers = repository.listServers("/repo")

            assertEquals(listOf("context7", "github", "linear"), servers.map { it.name })
            assertEquals(McpServerStatus.Connecting, servers.last().status)
            assertEquals("OAuth token expired", servers[1].errorMessage)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains("/api/mcp"),
            )
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
        }

    @Test
    fun listServers_httpFailure_mapsToMcpError() =
        runTest {
            val client =
                testClient {
                    respond(content = "nope", status = HttpStatusCode.InternalServerError)
                }
            val repository = DefaultMcpRepository(McpApi(client), McpDomainAdapter(), manager(ServerProtocol.V1))

            val error = runCatching { repository.listServers("/repo") }.exceptionOrNull()

            assertTrue(error is McpError)
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
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
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
