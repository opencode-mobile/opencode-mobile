package dev.opencode.mobile.domain.filesystem

import dev.opencode.mobile.data.remote.FilesystemApi
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

class FilesystemProtocolRepositoryTest {
    @Test
    fun v1GetPaths_usesUnprefixedPath() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("""{"home":"/home","worktree":"/repo","directory":"/repo"}""")
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V1),
                )

            val paths = repository.getPaths()

            assertEquals("/home", paths.home)
            assertEquals("/repo", paths.directory)
            assertTrue(requests.single().url.encodedPath.endsWith("/path"))
        }

    @Test
    fun v2GetPaths_mapsLocationDirectory() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "directory":"/repo",
                          "project":{"id":"p","directory":"/repo","canonical":"/repo"}
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val paths = repository.getPaths()

            assertEquals("/repo", paths.home)
            assertEquals("/repo", paths.directory)
            assertEquals("/repo", paths.worktree)
            assertTrue(requests.single().url.encodedPath.contains("/api/location"))
        }

    @Test
    fun v2ListDirectory_joinsRelativeEntries() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "location":{"directory":"/repo","project":{"id":"p","directory":"/repo","canonical":"/repo"}},
                          "data":[
                            {"path":"src","type":"directory"},
                            {"path":"README.md","type":"file"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultFilesystemRepository(
                    FilesystemApi(client),
                    FilesystemDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val nodes = repository.listDirectory("/repo")

            assertEquals(listOf("src"), nodes.map { it.name })
            assertEquals("/repo/src", nodes.single().absolutePath)
            assertTrue(nodes.single().isDirectory)
            assertTrue(requests.single().url.encodedPath.contains("/api/fs/list"))
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
            assertEquals(".", requests.single().url.parameters["path"])
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
