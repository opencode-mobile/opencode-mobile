package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
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
import org.junit.Test

/** The calls Hex Code's daily session adds to the sessions API (m2-api-notes.md, §1 to §5), with live-captured bodies. */
class SessionsApiDailySessionTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val model = V2ModelRefDto(id = "big-pickle", providerId = "opencode", variant = "low")

    @Test
    fun aPageSendsTheFiltersAndReadsBusyModelAndLocation() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond(
                        """{"data":[{"id":"ses_1","projectID":"p","model":{"id":"big-pickle","providerID":"opencode","variant":"default"},
                        "cost":0,"time":{"created":1,"updated":5,"busy":4,"idle":3},"title":"2026-11-05",
                        "location":{"directory":"/tmp/x"}}],"cursor":{"previous":"a","next":"b"}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val page = api.listSessionsPageV2(directory = "/tmp/x", search = "2026-11-05", rootsOnly = true, archived = false, limit = 20)

            val url = captured.single().url
            assertTrue(url.encodedPath.endsWith("/api/session"))
            assertEquals("/tmp/x", url.parameters["directory"])
            assertEquals("2026-11-05", url.parameters["search"])
            assertEquals("null", url.parameters["parentID"])
            assertEquals("false", url.parameters["archived"])
            assertEquals("20", url.parameters["limit"])
            val session = page.data.single()
            assertEquals(4L, session.time?.busy)
            assertEquals("/tmp/x", session.location?.directory)
            assertEquals("opencode", session.model?.providerId)
            assertEquals("b", page.cursor?.next)
        }

    @Test
    fun aLaterPageSendsTheCursorAlone() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond("""{"data":[],"cursor":{"previous":null,"next":null}}""", HttpStatusCode.OK, jsonHeaders)
                }

            api.listSessionsPageV2(directory = "/tmp/x", search = "2026", limit = 20, cursor = "eyJ")

            assertEquals(
                setOf("cursor"),
                captured
                    .single()
                    .url.parameters
                    .names(),
            )
        }

    @Test
    fun createSendsTheModelAndLocation() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond(
                        """{"data":{"id":"ses_2","title":"2026-11-05","time":{"created":1,"updated":1}}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            assertEquals("ses_2", api.createSessionV2(directory = "/tmp/x", title = "2026-11-05", model = model).id)

            assertEquals(
                """{"title":"2026-11-05","location":{"directory":"/tmp/x"},"model":{"id":"big-pickle","providerID":"opencode","variant":"low"}}""",
                captured.single().body.readText(),
            )
        }

    @Test
    fun createWithoutAModelSendsWhatItAlwaysDid() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond("""{"data":{"id":"ses_2"}}""", HttpStatusCode.OK, jsonHeaders)
                }

            api.createSessionV2(directory = "/tmp/x", title = "Fix login")

            assertEquals("""{"title":"Fix login","location":{"directory":"/tmp/x"}}""", captured.single().body.readText())
        }

    @Test
    fun switchingTheModelPostsTheModelRef() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond("", HttpStatusCode.NoContent)
                }

            assertTrue(api.switchSessionModelV2("ses_1", model))

            val request = captured.single()
            assertEquals("POST", request.method.value)
            assertTrue(request.url.encodedPath.endsWith("/api/session/ses_1/model"))
            assertEquals("""{"model":{"id":"big-pickle","providerID":"opencode","variant":"low"}}""", request.body.readText())
        }

    @Test
    fun theInstructionEntryIsPutAsAValueWithItsNewlinesEscaped() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond("", HttpStatusCode.NoContent)
                }

            assertTrue(api.putInstructionEntryV2("ses_1", "message-rules", "One.\n- \"Two\""))

            val request = captured.single()
            assertEquals("PUT", request.method.value)
            assertTrue(request.url.encodedPath.endsWith("/api/experimental/session/ses_1/instructions/entries/message-rules"))
            assertEquals("""{"value":"One.\n- \"Two\""}""", request.body.readText())
        }

    @Test
    fun theSessionsOwnApprovalsAndQuestionsAreListed() =
        runTest {
            val paths = mutableListOf<String>()
            val api =
                api { request ->
                    paths += request.url.encodedPath
                    if (request.url.encodedPath.endsWith("/permission")) {
                        respond(
                            """{"data":[{"id":"per_1","sessionID":"ses_1","action":"shell","resources":["ls"],"save":["ls *"]}]}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    } else {
                        respond(
                            """{"data":[{"id":"frm_1","sessionID":"ses_1","title":"Questions","metadata":{"kind":"question"},
                            "fields":[{"key":"q0","title":"Database","description":"Which database should the tests use?"}]}]}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                }

            assertEquals(listOf("ls"), api.listSessionPermissionsV2("ses_1").single().resources)
            assertEquals(
                "Which database should the tests use?",
                api
                    .listSessionFormsV2("ses_1")
                    .single()
                    .fields
                    .single()
                    .description,
            )
            assertTrue(paths[0].endsWith("/api/session/ses_1/permission"))
            assertTrue(paths[1].endsWith("/api/session/ses_1/form"))
        }

    @Test
    fun aSessionWithoutTheNewFieldsStillDecodes() =
        runTest {
            val api = api { respond("""{"data":{"id":"ses_3"}}""", HttpStatusCode.OK, jsonHeaders) }
            val session = api.getSessionV2("ses_3")
            assertNull(session.model)
            assertNull(session.time?.busy)
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
