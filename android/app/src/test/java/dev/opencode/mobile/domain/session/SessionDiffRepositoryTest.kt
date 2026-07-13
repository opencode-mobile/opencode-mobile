package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.SessionsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
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
}
