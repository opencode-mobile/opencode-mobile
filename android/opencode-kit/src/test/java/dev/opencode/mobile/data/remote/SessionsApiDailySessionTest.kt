package dev.opencode.mobile.data.remote

import dev.opencode.mobile.data.remote.dto.V2InboxFileDto
import dev.opencode.mobile.data.remote.dto.V2ModelRefDto
import dev.opencode.mobile.data.remote.dto.V2PromptFileDto
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun messagesArePagedAndALaterPageLeavesOrderToTheCursor() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond(
                        """{"data":[{"id":"msg_2","type":"assistant","content":[{"type":"tool","name":"todowrite",
                        "state":{"status":"completed","input":{"todos":[]},"metadata":{"todos":[{"content":"a","status":"pending"}]}}}]}],
                        "cursor":{"previous":"p","next":"n"}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val page = api.listSessionMessagesPageV2("ses_1", type = "assistant", order = "desc", limit = 50)
            api.listSessionMessagesPageV2("ses_1", type = "assistant", order = "desc", limit = 50, cursor = "c")

            val url = captured[0].url
            assertTrue(url.encodedPath.endsWith("/api/session/ses_1/message"))
            assertEquals("assistant", url.parameters["type"])
            assertEquals("desc", url.parameters["order"])
            assertEquals("50", url.parameters["limit"])
            // A later page sends type and limit again, but never order: the server refuses it with a cursor.
            val later = captured[1].url
            assertEquals(setOf("cursor", "type", "limit"), later.parameters.names())
            assertEquals("c", later.parameters["cursor"])
            assertEquals(
                "msg_2",
                page.data
                    .single()["id"]
                    ?.toString()
                    ?.trim('"'),
            )
            assertEquals("n", page.cursor?.next)
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

    @Test
    fun aTextPromptPostsTextAndDeliveryAndReadsTheInboxItem() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond(
                        """{"data":{"id":"msg_1","sessionID":"ses_1","time":{"created":5},"type":"user","payload":{"text":"Run ls"},"delivery":"queue"}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val item = api.promptTextV2("ses_1", "Run ls", "queue")

            assertEquals("msg_1", item.id)
            assertEquals("Run ls", item.payload?.text)
            val request = captured.single()
            assertTrue(request.url.encodedPath.endsWith("/api/session/ses_1/prompt"))
            assertEquals("""{"text":"Run ls","delivery":"queue"}""", request.body.readText())
        }

    @Test
    fun aPromptWithImagesSendsThemAsDataUrlsWithNamesAndNoLineBreaks() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api =
                api { request ->
                    captured += request
                    respond(
                        """{"data":{"id":"msg_2","type":"user","payload":{"text":"What is this?","files":[{"data":"/9j/4AAQ","mime":"image/jpeg",
                        "source":{"type":"inline"},"name":"Photo 1.jpg"}]},"delivery":"steer"}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            // 3 000 bytes: base64 that a wrapping encoder would split into 76-character lines.
            val b64 =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(ByteArray(3_000) { it.toByte() })
            val files =
                listOf(
                    V2PromptFileDto(uri = "data:image/jpeg;base64,$b64", name = "Photo 1.jpg"),
                    V2PromptFileDto(uri = "data:image/jpeg;base64,$b64", name = "Screenshot 1.jpg"),
                )

            val item = api.promptTextV2("ses_1", "What is this?", "steer", files)

            val body = captured.single().body.readText()
            assertTrue("no line breaks in the body", '\n' !in body && "\\n" !in body)
            val sent = Json.parseToJsonElement(body).jsonObject
            assertEquals("What is this?", sent["text"]?.jsonPrimitive?.content)
            assertEquals("steer", sent["delivery"]?.jsonPrimitive?.content)
            val sentFiles = sent["files"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("Photo 1.jpg", "Screenshot 1.jpg"), sentFiles.map { it["name"]?.jsonPrimitive?.content })
            assertEquals("data:image/jpeg;base64,$b64", sentFiles[0]["uri"]?.jsonPrimitive?.content)
            assertEquals(setOf("uri", "name"), sentFiles[0].keys)
            // The reply's file keeps its name and type, never its data.
            assertEquals(listOf(V2InboxFileDto(name = "Photo 1.jpg", mime = "image/jpeg")), item.payload?.files)
        }

    @Test
    fun theInboxReadsFileNamesAndTypesWithoutTheirData() =
        runTest {
            val data = "A".repeat(700_000)
            val api =
                api {
                    respond(
                        """{"data":[{"id":"msg_1","sessionID":"ses_1","time":{"created":5},"type":"user",
                        "payload":{"text":"Queued: what letters?","files":[{"data":"$data","mime":"image/jpeg","source":{"type":"inline"},
                        "name":"queued.jpg"},{"data":"iVBOR","mime":"image/png","name":"b.png"}]},"delivery":"queue"},
                        {"id":"msg_2","type":"user","payload":{"text":"No files"},"delivery":"queue"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

            val inbox = api.listInboxV2("ses_1")

            assertEquals(
                listOf(V2InboxFileDto("queued.jpg", "image/jpeg"), V2InboxFileDto("b.png", "image/png")),
                inbox[0].payload?.files,
            )
            assertEquals(emptyList<V2InboxFileDto>(), inbox[1].payload?.files)
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
