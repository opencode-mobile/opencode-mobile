package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.data.remote.AgentsApi
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolRepositoryTest {
    @Test
    fun v2ListAgents_mapsIdAndFiltersPrimary() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {"data":[
                          {"id":"build","mode":"primary","hidden":false,"description":"Primary"},
                          {"id":"explore","mode":"subagent","hidden":false,"description":"Explorer"}
                        ]}
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultAgentRepository(
                    AgentsApi(client),
                    AgentDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val agents = repository.listMentionableAgents("/repo")

            assertEquals(listOf("explore"), agents.map { it.name })
            assertEquals("Explorer", agents.single().description)
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains("/api/agent"),
            )
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
        }

    @Test
    fun v2ListSelectableAgents_mapsIdAndKeepsPrimary() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {"data":[
                          {"id":"build","mode":"primary","hidden":false,"description":"Primary"},
                          {"id":"review","mode":"all","hidden":false,"description":"Reviews work"},
                          {"id":"title","mode":"primary","hidden":true,"description":"Hidden"},
                          {"id":"explore","mode":"subagent","hidden":false,"description":"Explorer"}
                        ]}
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultAgentRepository(
                    AgentsApi(client),
                    AgentDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val agents = repository.listSelectableAgents("/repo")

            assertEquals(listOf("build", "review"), agents.map { it.id })
            assertEquals(listOf("Build", "Review"), agents.map { it.displayName })
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .contains("/api/agent"),
            )
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
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
