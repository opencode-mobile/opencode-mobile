package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.mobile.data.remote.SessionsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reconnect read-back and the setup calls over OpenCode's API, with live-captured bodies (m2-api-notes.md). */
class OpenCodeTodayApiTest {
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<String>()

    private fun api(routes: (String) -> String?): OpenCodeTodayApi {
        val json = Json { ignoreUnknownKeys = true }
        val client =
            HttpClient(
                MockEngine { request ->
                    val key = "${request.method.value} ${request.url.encodedPath}"
                    val body = (request.body as? TextContent)?.text
                    requests += if (body != null) "$key $body" else key
                    val reply =
                        routes(
                            request.url.encodedPath +
                                if (request.url.parameters["parentID"].let { it != null && it != "null" }) "?parentID" else "",
                        )
                    if (reply == null) respond("", HttpStatusCode.NoContent) else respond(reply, HttpStatusCode.OK, headers)
                },
            ) { install(ContentNegotiation) { json(json) } }
        return OpenCodeTodayApi(SessionsApi(client, json)) { 777 }
    }

    @Test
    fun aBusySessionWaitingOnARunningSubagentsQuestion() =
        runTest {
            val today =
                api { path ->
                    when (path) {
                        "/api/session/active" -> """{"data":{"ses_1":{"type":"running"},"ses_c1":{"type":"running"}}}"""
                        "/api/session/ses_1" ->
                            """{"data":{"id":"ses_1","time":{"created":1,"updated":2,"busy":1793914371232,"idle":1793914367052}}}"""
                        "/api/session?parentID" ->
                            """{"data":[{"id":"ses_c1","parentID":"ses_1"},{"id":"ses_c2","parentID":"ses_1"}],"cursor":{"next":null}}"""
                        "/api/session/ses_1/inbox" ->
                            """{"data":[{"id":"msg_1","sessionID":"ses_1","time":{"created":3},"type":"user",""" +
                                """"payload":{"text":"Also"},"delivery":"queue"}]}"""
                        "/api/session/ses_1/permission", "/api/session/ses_1/form", "/api/session/ses_c1/permission" -> """{"data":[]}"""
                        "/api/session/ses_c1/form" ->
                            """{"data":[{"id":"frm_1","sessionID":"ses_c1","title":"Questions","fields":[{"key":"q0","title":"Database","description":"Which database?"}]}]}"""
                        else -> error("unexpected $path")
                    }
                }
            val snapshot = today.snapshot("ses_1")
            assertEquals(1793914371232L, snapshot.busySince)
            assertTrue(snapshot.hasRun)
            assertEquals(listOf("ses_c1", "ses_c2"), snapshot.children)
            assertEquals(listOf(PendingItem("frm_1", "ses_c1", PendingItem.Kind.Question, "Which database?")), snapshot.pending)
            assertEquals(listOf(InboxEntry("msg_1", "queue", "Also")), snapshot.inbox)
            // Only running subagents are asked; the idle one isn't.
            assertTrue(requests.none { "ses_c2/" in it })
        }

    @Test
    fun busyComesFromActiveNotFromComparingBusyWithIdle() =
        runTest {
            // A rejected question leaves time.busy after time.idle, but the session isn't running.
            val today =
                api { path ->
                    when (path) {
                        "/api/session/active" -> """{"data":{}}"""
                        "/api/session/ses_1" -> """{"data":{"id":"ses_1","time":{"created":1,"updated":2,"busy":500,"idle":400}}}"""
                        "/api/session?parentID" -> """{"data":[],"cursor":{"next":null}}"""
                        else -> """{"data":[]}"""
                    }
                }
            val snapshot = today.snapshot("ses_1")
            assertNull(snapshot.busySince)
            assertTrue(snapshot.hasRun)
        }

    @Test
    fun findCreateAndRulesSendWhatThePlanSays() =
        runTest {
            val today =
                api { path ->
                    when (path) {
                        "/api/session" ->
                            if (requests.last().startsWith("GET")) {
                                """{"data":[{"id":"ses_9","title":"2026-11-05","time":{"created":1,"updated":2},"location":{"directory":"/tmp/p"}}],"cursor":{"next":"eyJ"}}"""
                            } else {
                                """{"data":{"id":"ses_new","title":"2026-11-05","time":{"created":1,"updated":1},"location":{"directory":"/tmp/p"}}}"""
                            }
                        else -> null
                    }
                }
            val page = today.sessionsPage("/tmp/p", "2026-11-05", 20, cursor = null)
            assertEquals(listOf(SessionListing("ses_9", "2026-11-05", "/tmp/p", 2, null)), page.sessions)
            assertEquals("eyJ", page.next)
            val created = today.create("2026-11-05", "/tmp/p", ModelChoice("opencode", "big-pickle", "low"))
            assertEquals("ses_new", created.id)
            today.putMessageRules("ses_new")
            today.switchModel("ses_new", ModelChoice("opencode", "beam-free"))
            assertEquals(
                listOf(
                    "GET /api/session",
                    """POST /api/session {"title":"2026-11-05","location":{"directory":"/tmp/p"},"model":{"id":"big-pickle","providerID":"opencode","variant":"low"}}""",
                    "PUT /api/experimental/session/ses_new/instructions/entries/message-rules " +
                        Json.encodeToString(mapOf("value" to SessionPrompts.MESSAGE_RULES)),
                    """POST /api/session/ses_new/model {"model":{"id":"beam-free","providerID":"opencode"}}""",
                ),
                requests,
            )
        }

    @Test
    fun pendingItemsComeOldestFirstWhateverSessionTheyBelongTo() =
        runTest {
            val today =
                api { path ->
                    when (path) {
                        "/api/session/active" -> """{"data":{"ses_1":{"type":"running"},"ses_c1":{"type":"running"}}}"""
                        "/api/session/ses_1" -> """{"data":{"id":"ses_1","time":{"created":1,"updated":2,"busy":5}}}"""
                        "/api/session?parentID" -> """{"data":[{"id":"ses_c1","parentID":"ses_1"}],"cursor":{"next":null}}"""
                        "/api/session/ses_1/permission" ->
                            """{"data":[{"id":"per_1addc0000001aaaa","sessionID":"ses_1","action":"edit","resources":[],"message":"Edit the config"}]}"""
                        "/api/session/ses_c1/form" ->
                            """{"data":[{"id":"frm_1addb0000001bbbb","sessionID":"ses_c1","title":"Questions","fields":[{"key":"q0","title":"Database"}]}]}"""
                        else -> """{"data":[]}"""
                    }
                }
            val pending = today.snapshot("ses_1").pending
            assertEquals(listOf("frm_1addb0000001bbbb", "per_1addc0000001aaaa"), pending.map { it.id })
            assertEquals(listOf("Database", "Edit the config"), pending.map { it.text })
        }
}
