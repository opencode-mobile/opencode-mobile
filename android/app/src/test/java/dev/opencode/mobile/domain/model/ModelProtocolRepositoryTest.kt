package dev.opencode.mobile.domain.model

import dev.opencode.mobile.data.remote.ModelsApi
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

class ModelProtocolRepositoryTest {
    @Test
    fun v2ListModelOptions_joinsProviderAndModelLists() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val responses =
                ArrayDeque(
                    listOf(
                        """{"data":[{"id":"anthropic","name":"Anthropic","disabled":false}]}""",
                        """
                        {"data":[{
                          "id":"claude-sonnet",
                          "providerID":"anthropic",
                          "name":"Claude Sonnet",
                          "enabled":true,
                          "status":"active",
                          "variants":[{"id":"high"},{"id":"low"}],
                          "capabilities":{"tools":true,"input":["text","image"]},
                          "limit":{"context":200000,"output":8192}
                        }]}
                        """.trimIndent(),
                    ),
                )
            val client =
                testClient { request ->
                    requests += request
                    respondJson(responses.removeFirst())
                }
            val repository =
                DefaultModelRepository(
                    ModelsApi(client, json),
                    ModelDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val catalog = repository.listModelOptions("/repo")

            assertEquals(1, catalog.options.size)
            assertEquals("anthropic", catalog.options.single().providerId)
            assertEquals("Anthropic", catalog.options.single().providerName)
            assertEquals("claude-sonnet", catalog.options.single().modelId)
            assertEquals(listOf("high", "low"), catalog.options.single().variants)
            assertEquals(true, catalog.options.single().supportsImageInput)
            assertEquals(false, catalog.options.single().supportsPdfInput)
            assertNull(catalog.serverDefault)
            assertEquals(2, requests.size)
            assertTrue(requests[0].url.encodedPath.contains("/api/provider"))
            assertTrue(requests[1].url.encodedPath.contains("/api/model"))
            assertEquals("/repo", requests[0].url.parameters["location[directory]"])
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
