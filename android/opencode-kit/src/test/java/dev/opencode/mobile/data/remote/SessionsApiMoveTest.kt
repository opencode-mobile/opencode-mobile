package dev.opencode.mobile.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
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
import org.junit.Assert.fail
import org.junit.Test

class SessionsApiMoveTest {
    @Test
    fun movePostsOnlyTheDirectoryAndAccepts204() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond("", HttpStatusCode.NoContent)
                }

            assertTrue(api.moveSessionV2("ses_1", "/repo/.worktrees/feature"))

            val request = captured.single()
            assertEquals("POST", request.method.value)
            assertTrue(request.url.encodedPath.endsWith("/api/session/ses_1/move"))
            assertEquals("""{"directory":"/repo/.worktrees/feature"}""", request.body.readText())
        }

    @Test
    fun moveKeepsTheServerMessageOnFailure() =
        runTest {
            val api =
                api {
                    respond(
                        """{"_tag":"LocationUnavailableError","message":"Destination is not a directory"}""",
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            try {
                api.moveSessionV2("ses_1", "/nope")
                fail("expected failure")
            } catch (error: ResponseException) {
                assertEquals(400, error.response.status.value)
                assertTrue(error.message.orEmpty().contains("Destination is not a directory"))
            }
        }

    @Test
    fun generatePostsThePromptAndReadsTheText() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond(
                        """{"data":{"text":"Two files."}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            assertEquals("Two files.", api.generateSessionTextV2("ses_1", "Instructions\n\nWhat changed?"))

            val request = captured.single()
            assertEquals("POST", request.method.value)
            assertTrue(request.url.encodedPath.endsWith("/api/session/ses_1/generate"))
            assertEquals("""{"prompt":"Instructions\n\nWhat changed?"}""", request.body.readText())
        }

    @Test
    fun generateSurfacesNotFoundAsAStatus() =
        runTest {
            val api = api { respond("", HttpStatusCode.NotFound) }

            try {
                api.generateSessionTextV2("ses_1", "Why?")
                fail("expected failure")
            } catch (error: ResponseException) {
                assertEquals(404, error.response.status.value)
            }
        }

    private fun api(handler: MockRequestHandler): SessionsApi {
        val json = Json { ignoreUnknownKeys = true }
        val client =
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json(json) }
            }
        return SessionsApi(client, json)
    }
}

private fun OutgoingContent.readText(): String =
    when (this) {
        is TextContent -> text
        is OutgoingContent.ByteArrayContent -> String(bytes())
        else -> ""
    }
