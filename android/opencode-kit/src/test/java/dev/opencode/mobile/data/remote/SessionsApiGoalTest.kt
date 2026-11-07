package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.V2PromptFileDto
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The calls Hex Code's goal needs (m5-proposal.md §1.1–1.2), with bodies captured live on v2.2.5. */
class SessionsApiGoalTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun theGoalIsReadWithTheRpcGetAndNullIsNone() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            var reply = """{"output":null}"""
            val api =
                api { request ->
                    captured += request
                    respond(reply, HttpStatusCode.OK, jsonHeaders)
                }

            assertNull(api.goalV2("ses_1", "/tmp/p"))
            val request = captured.single()
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.encodedPath.endsWith("/api/rpc/opencode.goal/get"))
            assertEquals("/tmp/p", request.url.parameters["location[directory]"])
            val body = Json.parseToJsonElement(request.body.text()).jsonObject
            assertEquals(
                "ses_1",
                body["input"]
                    ?.jsonObject
                    ?.get("sessionID")
                    ?.jsonPrimitive
                    ?.content,
            )

            reply =
                """{"output":{"id":"32ebdf97","objective":"Create the files one.txt, two.txt and three.txt",
                "status":"complete","reason":"Verified all three files exist","rounds":2,"maxRounds":50,"stalls":0,
                "boot":"961e4e8d","updatedAt":1794007563460}}"""
            val goal = api.goalV2("ses_1", "/tmp/p")!!
            assertEquals("32ebdf97", goal.id)
            assertEquals("Create the files one.txt, two.txt and three.txt", goal.objective)
            assertEquals("complete", goal.status)
            assertEquals("Verified all three files exist", goal.reason)
            assertEquals(2, goal.rounds)
            assertEquals(50, goal.maxRounds)
        }

    @Test
    fun aNewGoalHasNoReason() =
        runTest {
            val api =
                api {
                    respond(
                        """{"output":{"id":"g1","objective":"Ship it","status":"active","rounds":0,"maxRounds":50,"stalls":0}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val goal = api.goalV2("ses_1", null)!!
            assertNull(goal.reason)
            assertEquals(0, goal.rounds)
        }

    @Test
    fun aCommandSendsItsTextNotArgumentsAndLeavesFilesAndDeliveryOut() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond("", HttpStatusCode.NoContent)
                }

            api.runSessionCommandV2("ses_1", "goal", "pause")
            api.runSessionCommandV2("ses_1", "goal", "Fix the login", listOf(V2PromptFileDto("data:image/jpeg;base64,AAAA", "Photo 1.jpg")))

            assertEquals(HttpMethod.Post, captured[0].method)
            assertTrue(captured[0].url.encodedPath.endsWith("/api/session/ses_1/command"))
            val pause = Json.parseToJsonElement(captured[0].body.text()).jsonObject
            assertEquals(setOf("name", "text"), pause.keys)
            assertEquals("goal", pause["name"]?.jsonPrimitive?.content)
            assertEquals("pause", pause["text"]?.jsonPrimitive?.content)
            val set = Json.parseToJsonElement(captured[1].body.text()).jsonObject
            assertEquals(setOf("name", "text", "files"), set.keys)
            val file = set["files"]!!.jsonArray.single().jsonObject
            assertEquals("Photo 1.jpg", file["name"]?.jsonPrimitive?.content)
        }

    @Test
    fun aStaleCommandIsAnHttpErrorWithTheServersMessage() =
        runTest {
            val api =
                api {
                    respond(
                        """{"_tag":"CommandExecutionError","command":"goal","message":"No active goal to pause."}""",
                        HttpStatusCode.InternalServerError,
                        jsonHeaders,
                    )
                }

            val error = runCatching { api.runSessionCommandV2("ses_1", "goal", "pause") }.exceptionOrNull()

            assertEquals(500, (error as HttpBodyResponseException).response.status.value)
            assertTrue(error.message!!.contains("No active goal to pause."))
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
