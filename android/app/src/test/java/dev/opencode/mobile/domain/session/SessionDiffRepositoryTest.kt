package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.SessionsApi
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

class SessionDiffRepositoryTest {
    @Test fun scopesRequestAndMapsStructuredDiff() =
        runTest {
            var captured: HttpRequestData? = null
            val json = Json { ignoreUnknownKeys = true }
            val client =
                HttpClient(
                    MockEngine { request ->
                        captured = request
                        respond(
                            content =
                                """[{"file":"App.kt","patch":"@@ -1 +1 @@\n-old\n+new",""" +
                                    """"additions":1,"deletions":1,"status":"modified"}]""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(json) } }
            val repository = DefaultSessionRepository(SessionsApi(client, json), SessionDomainAdapter())

            val result = repository.getSessionDiff("ses-1", "msg + 1", "/repo work")

            assertEquals("App.kt", result.single().path)
            assertEquals(FileDiffStatus.Modified, result.single().status)
            assertEquals("msg + 1", captured?.url?.parameters?.get("messageID"))
            assertEquals("/repo work", captured?.url?.parameters?.get("directory"))
            assertTrue(captured?.url.toString().contains("session/ses-1/diff"))
        }

    @Test fun loadsVcsMetadataAndGitDiffWithWorktreeScopeAndContext() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val responses =
                ArrayDeque(
                    listOf(
                        """{"branch":"feature/review","default_branch":"main"}""",
                        """[{"file":"App.kt","patch":"@@ -1 +1 @@\n-old\n+new","additions":1,"deletions":1,"status":"modified"}]""",
                    ),
                )
            val json = Json { ignoreUnknownKeys = true }
            val client =
                testClient(json) { request ->
                    requests += request
                    respond(
                        content = responses.removeFirst(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val repository = DefaultSessionRepository(SessionsApi(client, json), SessionDomainAdapter())

            val info = repository.getVcsInfo("/repo work")
            val files = repository.getVcsDiff(VcsDiffMode.WorkingTree, "/repo work", context = 12)

            assertEquals(VcsInfo("feature/review", "main"), info)
            assertEquals("App.kt", files.single().path)
            assertEquals("/repo work", requests[0].url.parameters["directory"])
            assertTrue(requests[0].url.toString().contains("/vcs"))
            assertEquals("git", requests[1].url.parameters["mode"])
            assertEquals("12", requests[1].url.parameters["context"])
            assertEquals("/repo work", requests[1].url.parameters["directory"])
            assertTrue(requests[1].url.toString().contains("/vcs/diff"))
        }

    @Test fun mapsBranchSourceToBranchMode() =
        runTest {
            var captured: HttpRequestData? = null
            val json = Json { ignoreUnknownKeys = true }
            val client =
                testClient(json) { request ->
                    captured = request
                    respond(
                        content = "[]",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val repository = DefaultSessionRepository(SessionsApi(client, json), SessionDomainAdapter())

            repository.getVcsDiff(VcsDiffMode.MainBranch, "/repo", context = 12)

            assertEquals("branch", captured?.url?.parameters?.get("mode"))
        }

    private fun testClient(
        json: Json,
        handler: MockRequestHandler,
    ) = HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(json) } }
}
