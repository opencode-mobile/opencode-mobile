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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun v2CreatePostsProjectFromNameAndBranchAndReturnsTheDirectory() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api(captured) { request ->
                    captured += request
                    respond(
                        """{"directory":"/data/worktree/ab12cd/brave-otter"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val directory = api.createV2("proj", "/repo", "brave-otter", "feature/x")

            assertEquals("/data/worktree/ab12cd/brave-otter", directory)
            val request = captured.single()
            assertEquals("POST", request.method.value)
            assertTrue(request.url.encodedPath.endsWith("/api/worktree"))
            assertEquals(
                """{"projectID":"proj","from":"/repo","name":"brave-otter","branch":"feature/x"}""",
                request.body.readText(),
            )
        }

    @Test
    fun v2CreateOmitsBranchWithoutABase() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api(captured) { request ->
                    captured += request
                    respond("""{"directory":"/wt/one"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            api.createV2("proj", "/repo", "one", null)

            val body = captured.single().body.readText()
            assertEquals("""{"projectID":"proj","from":"/repo","name":"one"}""", body)
            assertFalse(body.contains("branch"))
        }

    @Test
    fun v2CreateFailureCarriesStatusAndBody() =
        runTest {
            val body = """{"_tag":"WorktreeError","name":"WorktreeError","data":{"message":"fatal: invalid reference: nope"}}"""
            val api = api(mutableListOf()) { respond(body, HttpStatusCode.BadRequest) }

            try {
                api.createV2("proj", "/repo", "one", "nope")
                fail("expected a failure")
            } catch (error: WorktreeHttpException) {
                assertEquals(400, error.statusCode)
                assertEquals(body, error.body)
            }
        }

    @Test
    fun v1CreatePostsNameInTheSourceDirectoryAndDecodesTheAnswer() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api(captured) { request ->
                    captured += request
                    respond(
                        """{"name":"probe-v1-tidy-tiger","branch":"opencode/probe-v1-tidy-tiger","directory":"/wt/probe-v1-tidy-tiger"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val created = api.createV1("/repo", "probe-v1")

            assertEquals(
                V1CreatedWorktree(
                    name = "probe-v1-tidy-tiger",
                    branch = "opencode/probe-v1-tidy-tiger",
                    directory = "/wt/probe-v1-tidy-tiger",
                ),
                created,
            )
            val request = captured.single()
            assertEquals("POST", request.method.value)
            assertTrue(request.url.encodedPath.endsWith("/experimental/worktree"))
            assertEquals("/repo", request.url.parameters["directory"])
            assertEquals("""{"name":"probe-v1"}""", request.body.readText())
        }

    @Test
    fun v1CreateReports404AsAnHttpFailure() =
        runTest {
            val api = api(mutableListOf()) { respond("Not Found", HttpStatusCode.NotFound) }

            try {
                api.createV1("/repo", "one")
                fail("expected a failure")
            } catch (error: WorktreeHttpException) {
                assertEquals(404, error.statusCode)
            }
        }

    @Test
    fun v2BranchesReadTheLocationResponseAtTheDirectory() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api(captured) { request ->
                    captured += request
                    respond(
                        """{"location":{"directory":"/repo"},"data":["main","feature/x","origin/main"]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            assertEquals(listOf("main", "feature/x", "origin/main"), api.branchesV2("/repo"))
            val request = captured.single()
            assertTrue(request.url.encodedPath.endsWith("/api/vcs/branch"))
            assertEquals("/repo", request.url.parameters["location[directory]"])
        }

    @Test
    fun v2BranchesAcceptABareArray() =
        runTest {
            val api =
                api(mutableListOf()) {
                    respond("""["main"," ","dev"]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            assertEquals(listOf("main", "dev"), api.branchesV2("/repo"))
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
