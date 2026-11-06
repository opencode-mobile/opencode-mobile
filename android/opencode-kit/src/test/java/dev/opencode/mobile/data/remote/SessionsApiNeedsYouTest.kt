package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.V2PermissionReplyRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The calls Hex Code's approvals, questions and Stop need (m4-proposal.md §1), with bodies captured live on v2.2.5. */
class SessionsApiNeedsYouTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun aRejectCarriesItsNoteAndOnceLeavesTheNoteOut() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond("", HttpStatusCode.NoContent)
                }

            api.respondToPermissionV2("ses_child", "per_1", V2PermissionReplyRequestDto("reject", "Use printf instead."))
            api.respondToPermissionV2("ses_child", "per_2", V2PermissionReplyRequestDto("once"))

            assertTrue(captured[0].url.encodedPath.endsWith("/api/session/ses_child/permission/per_1/reply"))
            val reject = Json.parseToJsonElement(captured[0].body.text()).jsonObject
            assertEquals("reject", reject["decision"]?.jsonPrimitive?.content)
            assertEquals("Use printf instead.", reject["message"]?.jsonPrimitive?.content)
            val once = Json.parseToJsonElement(captured[1].body.text()).jsonObject
            assertEquals(setOf("decision"), once.keys)
        }

    @Test
    fun dismissingAFormSendsItsNoteAsAQuery() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond("", HttpStatusCode.NoContent)
                }

            api.cancelFormV2("ses_1", "frm_1", "Carry on with your best judgement.")
            api.cancelFormV2("ses_1", "frm_2")

            assertEquals(HttpMethod.Delete, captured[0].method)
            assertTrue(captured[0].url.encodedPath.endsWith("/api/session/ses_1/form/frm_1"))
            assertEquals("Carry on with your best judgement.", captured[0].url.parameters["message"])
            assertNull(captured[1].url.parameters["message"])
        }

    @Test
    fun interruptSaysWhetherARunWasStopped() =
        runTest {
            var interrupted = true
            val api = api { respond("""{"interrupted":$interrupted}""", HttpStatusCode.OK, jsonHeaders) }

            assertTrue(api.interruptSessionResultV2("ses_1"))
            interrupted = false
            assertFalse(api.interruptSessionResultV2("ses_1"))
        }

    @Test
    fun aMissingRequestIsAnHttpError() =
        runTest {
            val api =
                api {
                    respond(
                        """{"_tag":"PermissionNotFoundError","requestID":"per_x","message":"Permission request not found: per_x"}""",
                        HttpStatusCode.NotFound,
                        jsonHeaders,
                    )
                }

            val error = runCatching { api.respondToPermissionV2("ses_1", "per_x", V2PermissionReplyRequestDto("once")) }.exceptionOrNull()

            assertEquals(404, (error as HttpBodyResponseException).response.status.value)
        }

    @Test
    fun shellsAreListedForTheFolderWithTheirSession() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond(
                        """{"location":{"directory":"/tmp/p"},"data":[{"id":"sh_1","status":"running","command":"bun dev",
                        "cwd":"/tmp/p","metadata":{"sessionID":"ses_1"},"time":{"started":1793986674864}}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val shells = api.listShellsV2("/tmp/p")

            assertEquals("/tmp/p", captured.single().url.parameters["location[directory]"])
            val shell = shells.single()
            assertEquals("bun dev", shell.command)
            assertEquals(
                "ses_1",
                shell.metadata
                    ?.get("sessionID")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(1793986674864.0, shell.time?.started)
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

private fun OutgoingContent.text(): String =
    when (this) {
        is TextContent -> text
        is OutgoingContent.ByteArrayContent -> String(bytes())
        else -> ""
    }
