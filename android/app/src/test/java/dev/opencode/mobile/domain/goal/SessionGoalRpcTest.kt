package dev.opencode.mobile.domain.goal

import dev.opencode.mobile.data.remote.HttpBodyResponseException
import dev.opencode.mobile.data.remote.SessionsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The goal's RPC read and its `/goal` controls, through the kit's [SessionsApi] into [SessionGoal]. */
class SessionGoalRpcTest {
    @Test
    fun getCallsTheGoalRpcAtTheSessionLocation() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respondJson(
                        """{"output":{"id":"goal_1","objective":"Ship it","status":"complete","reason":"Done",""" +
                            """"rounds":4,"maxRounds":50,"boot":"b","updatedAt":9}}""",
                    )
                }

            val goal = SessionGoalDomainAdapter.fromState(api.goalV2("ses_1", "/Users/example/project"))

            val request = captured.single()
            assertEquals("POST", request.method.value)
            assertTrue(request.url.encodedPath.endsWith("/api/rpc/opencode.goal/get"))
            assertEquals("/Users/example/project", request.url.parameters["location[directory]"])
            assertEquals("""{"input":{"sessionID":"ses_1"}}""", request.body.bodyText())
            assertEquals(SessionGoalStatus.Complete, goal?.status)
            assertEquals("Done", goal?.reason)
        }

    @Test
    fun getReadsNoGoalAsNull() =
        runTest {
            val api = api { respondJson("""{"output":null}""") }

            assertNull(api.goalV2("ses_1", null))
        }

    @Test
    fun getFailsWithoutTheGoalPlugin() =
        runTest {
            val api = api { respond("""{"_tag":"RpcError","message":"Unknown RPC"}""", HttpStatusCode.NotFound) }

            try {
                api.goalV2("ses_1", null)
                fail("expected failure")
            } catch (error: HttpBodyResponseException) {
                assertEquals(404, error.response.status.value)
            }
        }

    @Test
    fun commandSendsTheGoalControlAndKeepsTheServerMessage() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            var fail = false
            val api =
                api { request ->
                    captured += request
                    if (fail) {
                        respond(
                            """{"_tag":"CommandError","message":"No active goal to pause."}""",
                            HttpStatusCode.BadRequest,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond("", HttpStatusCode.NoContent)
                    }
                }

            api.runSessionCommandV2("ses_1", SessionGoalLimits.COMMAND, SessionGoalAction.Pause.commandText)
            assertTrue(
                captured
                    .single()
                    .url.encodedPath
                    .endsWith("/api/session/ses_1/command"),
            )
            assertEquals("""{"name":"goal","text":"pause"}""", captured.single().body.bodyText())

            fail = true
            try {
                api.runSessionCommandV2("ses_1", SessionGoalLimits.COMMAND, SessionGoalAction.Pause.commandText)
                fail("expected failure")
            } catch (error: HttpBodyResponseException) {
                assertEquals("No active goal to pause", SessionGoalDomainAdapter.errorMessage(error))
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

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun OutgoingContent.bodyText(): String =
        when (this) {
            is TextContent -> text
            is OutgoingContent.ByteArrayContent -> String(bytes())
            else -> ""
        }
}
