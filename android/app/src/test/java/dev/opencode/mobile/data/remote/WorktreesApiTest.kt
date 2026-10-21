package dev.opencode.mobile.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreesApiTest {
    @Test
    fun listsV2ByProjectIdAndIgnoresStrategy() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api(captured) { request ->
                    respond(
                        """[{"directory":"/worktrees/crisp-cactus","strategy":"git"}]""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    ).also { captured += request }
                }

            assertEquals(listOf("/worktrees/crisp-cactus"), api.listV2("proj"))
            assertEquals("proj", captured.single().url.parameters["projectID"])
            assertTrue(captured.single().url.encodedPath.endsWith("/api/worktree"))
        }

    @Test
    fun refreshPostsProjectId() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api(captured) { request ->
                    captured += request
                    respond("", HttpStatusCode.NoContent)
                }

            api.refreshV2("proj")

            val request = captured.single()
            assertEquals("POST", request.method.value)
            assertTrue(request.url.encodedPath.endsWith("/api/worktree/refresh"))
            assertEquals("""{"projectID":"proj"}""", request.body.readText())
        }

    @Test
    fun v1ListsExperimentalWorktreeByDirectory() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api(captured) { request ->
                    captured += request
                    respond(
                        """["/abs/wt-a","/abs/wt-b"]""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            assertEquals(V1WorktreeHttp.Ok("""["/abs/wt-a","/abs/wt-b"]"""), api.listV1("/repo"))
            assertEquals("/repo", captured.single().url.parameters["directory"])
            assertTrue(captured.single().url.encodedPath.endsWith("/experimental/worktree"))
        }

    @Test
    fun v1ListReports404ForOlderServers() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api(captured) { request ->
                    captured += request
                    respond("missing", HttpStatusCode.NotFound)
                }

            assertEquals(V1WorktreeHttp.NotFound, api.listV1("/repo"))
        }

    private fun api(
        captured: MutableList<HttpRequestData>,
        handler: io.ktor.client.engine.mock.MockRequestHandler,
    ): WorktreesApi {
        val json = Json { ignoreUnknownKeys = true }
        val client =
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json(json) }
            }
        return WorktreesApi(client)
    }
}

private fun OutgoingContent.readText(): String =
    when (this) {
        is TextContent -> text
        is OutgoingContent.ByteArrayContent -> String(bytes())
        else -> ""
    }
