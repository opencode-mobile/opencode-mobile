package dev.opencode.mobile.domain.project

import dev.opencode.mobile.data.remote.ProjectsApi
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

class ProjectProtocolRepositoryTest {
    @Test
    fun v1ListProjects_usesProjectAndWorktree() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("""[{"id":"p1","worktree":"/Users/me/repo","vcs":"git"}]""")
                }

            val projects = repository(client, ServerProtocol.V1).listProjects()

            assertEquals(
                listOf(Project(id = "p1", worktree = "/Users/me/repo", displayName = "repo", isRepository = true)),
                projects,
            )
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/project"),
            )
        }

    @Test
    fun v2ListProjects_usesApiProjectAndCanonical() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """[{"id":"p2","canonical":"/Users/me/app","vcs":"git",""" +
                            """"time":{"created":1,"updated":1},"sandboxes":[]}]""",
                    )
                }

            val projects = repository(client, ServerProtocol.V2).listProjects()

            assertEquals(
                listOf(
                    Project(
                        id = "p2",
                        worktree = "/Users/me/app",
                        displayName = "app",
                        activityAtEpochMs = 1,
                        isRepository = true,
                    ),
                ),
                projects,
            )
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/api/project"),
            )
        }

    @Test
    fun v1ListProjects_decodesUpdatedTimeAsActivity() =
        runTest {
            val client =
                testClient {
                    respondJson(
                        """[{"id":"p1","worktree":"/Users/me/repo","vcs":"git",""" +
                            """"time":{"created":10,"updated":20,"initialized":15}},""" +
                            """{"id":"global","worktree":"/","time":{"created":1}}]""",
                    )
                }

            val projects = repository(client, ServerProtocol.V1).listProjects()

            assertEquals(listOf(20L, null), projects.map { it.activityAtEpochMs })
            assertEquals(listOf(true, false), projects.map { it.isRepository })
        }

    @Test
    fun v2ListProjects_prefersActiveTime() =
        runTest {
            val client =
                testClient {
                    respondJson("""[{"id":"p2","canonical":"/Users/me/app","time":{"created":1,"updated":2,"active":3}}]""")
                }

            val projects = repository(client, ServerProtocol.V2).listProjects()

            assertEquals(listOf(3L), projects.map { it.activityAtEpochMs })
        }

    @Test
    fun v2ListProjects_fallsBackToProjectWhenApiProjectServesHtml() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    if (request.url.encodedPath.endsWith("/api/project")) {
                        respond(
                            content = "<!doctype html><html></html>",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/html"),
                        )
                    } else {
                        respondJson("""[{"id":"p1","worktree":"/Users/me/repo","time":{"updated":7}}]""")
                    }
                }

            val projects = repository(client, ServerProtocol.V2).listProjects()

            assertEquals(
                listOf(Project(id = "p1", worktree = "/Users/me/repo", displayName = "repo", activityAtEpochMs = 7)),
                projects,
            )
            assertEquals(listOf("/api/project", "/project"), requests.map { it.url.encodedPath })
        }

    @Test
    fun v2ListProjects_fallsBackToProjectWhenApiProjectIsMissing() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    if (request.url.encodedPath.endsWith("/api/project")) {
                        respond(
                            content = "Not Found",
                            status = HttpStatusCode.NotFound,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    } else {
                        respondJson("""[{"id":"p1","worktree":"/Users/me/repo"}]""")
                    }
                }

            val projects = repository(client, ServerProtocol.V2).listProjects()

            assertEquals(listOf("/Users/me/repo"), projects.map { it.worktree })
            assertEquals(listOf("/api/project", "/project"), requests.map { it.url.encodedPath })
        }

    private suspend fun repository(
        client: HttpClient,
        protocol: ServerProtocol,
    ): DefaultProjectRepository {
        val manager = ActiveConnectionManager(FakeProfileStore())
        manager.applyProfile(
            ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = ""),
            serverProtocol = protocol,
        )
        return DefaultProjectRepository(ProjectsApi(client), ProjectDomainAdapter(), manager)
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
