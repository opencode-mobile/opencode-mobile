package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
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

class MessageProtocolRepositoryTest {
    @Test
    fun v1ListMessages_usesFlatWithPartsArray() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """[{"info":{"id":"msg_1","role":"user"},"parts":[{"type":"text","text":"Hi"}]}]""",
                    )
                }
            val router = router(client, ServerProtocol.V1)

            val messages = router.listMessages("ses_1", "/repo")

            assertEquals("msg_1", messages.single().id)
            assertEquals("Hi", messages.single().text)
            assertEquals("/repo", requests.single().url.parameters["directory"])
            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/session/ses_1/message"),
            )
        }

    @Test
    fun v2ListMessages_unwrapsEnvelopeAndProjectsDomainMessages() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondMessagesOrEmptyInbox(
                        request,
                        """
                        {
                          "data":[
                            {"id":"msg_user","type":"user","text":"Hello","time":{"created":1}},
                            {
                              "id":"msg_asst",
                              "type":"assistant",
                              "agent":"build",
                              "model":{"id":"gpt-5","providerID":"openai"},
                              "content":[{"type":"text","id":"t1","text":"Hi"}],
                              "time":{"created":2}
                            }
                          ],
                          "cursor":{"previous":null,"next":null}
                        }
                        """.trimIndent(),
                    )
                }
            val router = router(client, ServerProtocol.V2)

            val messages = router.listMessages("ses_2", "/repo")

            assertEquals(listOf("msg_user", "msg_asst"), messages.map { it.id })
            assertEquals("Hello", messages[0].text)
            assertEquals("Hi", messages[1].text)
            val messageRequest = requests.messageRequests().single()
            assertEquals(null, messageRequest.url.parameters["directory"])
            assertEquals("asc", messageRequest.url.parameters["order"])
            assertTrue(messageRequest.url.encodedPath.contains("/api/session/ses_2/message"))
        }

    @Test
    fun v1SendPrompt_usesPromptAsyncPartsBody() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond("", HttpStatusCode.NoContent)
                }
            val router = router(client, ServerProtocol.V1)

            router.sendPromptAsync(
                "ses_1",
                PromptSendOptions(text = "Hello"),
                "/repo",
            )

            assertTrue(
                requests
                    .single()
                    .url.encodedPath
                    .endsWith("/session/ses_1/prompt_async"),
            )
            val body = (requests.single().body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("\"parts\""))
            assertTrue(body.contains("Hello"))
        }

    @Test
    fun v2SendPrompt_switchesAgentModelThenPostsPrompt() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond("", HttpStatusCode.OK)
                }
            val router = router(client, ServerProtocol.V2)

            router.sendPromptAsync(
                "ses_2",
                PromptSendOptions(
                    text = "Hello",
                    attachments =
                        listOf(
                            Attachment(
                                id = "a1",
                                filename = "note.txt",
                                mime = "text/plain",
                                kind = AttachmentKind.Text,
                                dataUrl = "data:text/plain;base64,SGk=",
                                byteCount = 2,
                            ),
                        ),
                    model = PromptModelSelection(providerId = "openai", modelId = "gpt-5"),
                    variant = "high",
                    mode = "build",
                ),
                "/repo",
            )

            assertEquals(3, requests.size)
            assertTrue(requests[0].url.encodedPath.endsWith("/api/session/ses_2/agent"))
            assertTrue(requests[1].url.encodedPath.endsWith("/api/session/ses_2/model"))
            assertTrue(requests[2].url.encodedPath.endsWith("/api/session/ses_2/prompt"))
            val promptBody = (requests[2].body as io.ktor.http.content.TextContent).text
            assertTrue(promptBody.contains("\"text\":\"Hello\""))
            assertTrue(promptBody.contains("data:text/plain;base64,SGk="))
            assertTrue(promptBody.contains("steer"))
            assertTrue(!promptBody.contains("\"mime\""))
            assertTrue(!promptBody.contains("\"parts\""))
            assertTrue(!promptBody.contains("\"prompt\""))
        }

    @Test
    fun v2ListMessages_followsCursorPages() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    if (request.url.encodedPath.contains("/inbox")) {
                        respondJson("""{"data":[]}""")
                    } else {
                        val cursor = request.url.parameters["cursor"]
                        if (cursor == null) {
                            respondJson(
                                """{"data":[{"id":"msg_a","type":"user","text":"A","time":{"created":1}}],"cursor":{"next":"page-2"}}""",
                            )
                        } else {
                            respondJson(
                                """{"data":[{"id":"msg_b","type":"user","text":"B","time":{"created":2}}],"cursor":{"next":null}}""",
                            )
                        }
                    }
                }
            val router = router(client, ServerProtocol.V2)

            val messages = router.listMessages("ses_2", "/repo")

            assertEquals(listOf("msg_a", "msg_b"), messages.map { it.id })
            val messageRequests = requests.messageRequests()
            assertEquals(2, messageRequests.size)
            assertEquals("asc", messageRequests[0].url.parameters["order"])
            assertEquals(null, messageRequests[0].url.parameters["cursor"])
            assertEquals("page-2", messageRequests[1].url.parameters["cursor"])
            assertEquals(null, messageRequests[1].url.parameters["order"])
        }

    @Test
    fun v1ListMessageTail_sendsLimitWithoutBeforeAndReadsNextCursor() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond(
                        content = """[{"info":{"id":"msg_new","role":"user"},"parts":[{"type":"text","text":"Hi"}]}]""",
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                "X-Next-Cursor" to listOf("cursor-old"),
                            ),
                    )
                }
            val router = router(client, ServerProtocol.V1)

            val page = router.listMessagePage("ses_1", "/repo", MessageHistoryQuery.tail())

            assertEquals(listOf("msg_new"), page.messages.map { it.id })
            assertEquals("cursor-old", page.olderCursor)
            assertTrue(page.hasOlder)
            assertEquals("20", requests.single().url.parameters["limit"])
            assertEquals(null, requests.single().url.parameters["before"])
        }

    @Test
    fun v1ListOlderMessages_sendsLimitAndBefore() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond(
                        content = """[{"info":{"id":"msg_old","role":"user"},"parts":[{"type":"text","text":"Old"}]}]""",
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                HttpHeaders.Link to listOf("</session/ses_1/message?before=even-older>; rel=\"next\""),
                            ),
                    )
                }
            val router = router(client, ServerProtocol.V1)

            val page = router.listMessagePage("ses_1", "/repo", MessageHistoryQuery.older("cursor-old"))

            assertEquals(listOf("msg_old"), page.messages.map { it.id })
            assertEquals("even-older", page.olderCursor)
            assertEquals("200", requests.single().url.parameters["limit"])
            assertEquals("cursor-old", requests.single().url.parameters["before"])
        }

    @Test
    fun v2ListMessageTail_sendsLimitAndDescThenReturnsChronological() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondMessagesOrEmptyInbox(
                        request,
                        """
                        {
                          "data":[
                            {"id":"msg_new","type":"user","text":"New","time":{"created":2}},
                            {"id":"msg_old","type":"user","text":"Old","time":{"created":1}}
                          ],
                          "cursor":{"previous":null,"next":"older"}
                        }
                        """.trimIndent(),
                    )
                }
            val router = router(client, ServerProtocol.V2)

            val page = router.listMessagePage("ses_2", "/repo", MessageHistoryQuery.tail())

            assertEquals(listOf("msg_old", "msg_new"), page.messages.map { it.id })
            assertEquals("older", page.olderCursor)
            val messageRequest = requests.messageRequests().single()
            assertEquals("20", messageRequest.url.parameters["limit"])
            assertEquals("desc", messageRequest.url.parameters["order"])
            assertEquals(null, messageRequest.url.parameters["cursor"])
        }

    @Test
    fun v2ListOlderMessages_sendsCursorWithoutOrder() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "data":[
                            {"id":"msg_b","type":"user","text":"B","time":{"created":2}},
                            {"id":"msg_a","type":"user","text":"A","time":{"created":1}}
                          ],
                          "cursor":{"next":null}
                        }
                        """.trimIndent(),
                    )
                }
            val router = router(client, ServerProtocol.V2)

            val page = router.listMessagePage("ses_2", "/repo", MessageHistoryQuery.older("older"))

            assertEquals(listOf("msg_a", "msg_b"), page.messages.map { it.id })
            assertEquals(null, page.olderCursor)
            assertEquals(false, page.hasOlder)
            assertEquals("200", requests.single().url.parameters["limit"])
            assertEquals("older", requests.single().url.parameters["cursor"])
            assertEquals(null, requests.single().url.parameters["order"])
        }

    @Test
    fun v2ListMessageTail_reversesDescPageOnceIgnoringTimestamps() =
        runTest {
            val client =
                testClient { request ->
                    respondMessagesOrEmptyInbox(
                        request,
                        """
                        {
                          "data":[
                            {"id":"msg_new","type":"user","text":"New","time":{"created":1}},
                            {"id":"msg_old","type":"user","text":"Old","time":{"created":100}}
                          ],
                          "cursor":{"next":"older"}
                        }
                        """.trimIndent(),
                    )
                }
            val router = router(client, ServerProtocol.V2)

            val page = router.listMessagePage("ses_2", "/repo", MessageHistoryQuery.tail())

            assertEquals(listOf("msg_old", "msg_new"), page.messages.map { it.id })
        }

    @Test
    fun v2ListOlderMessages_reversesCursorDescPageOnceIgnoringTimestamps() =
        runTest {
            val client =
                testClient { _ ->
                    respondJson(
                        """
                        {
                          "data":[
                            {"id":"msg_b","type":"user","text":"B","time":{"created":1}},
                            {"id":"msg_a","type":"user","text":"A","time":{"created":100}}
                          ],
                          "cursor":{"next":null}
                        }
                        """.trimIndent(),
                    )
                }
            val router = router(client, ServerProtocol.V2)

            val page = router.listMessagePage("ses_2", "/repo", MessageHistoryQuery.older("older"))

            assertEquals(listOf("msg_a", "msg_b"), page.messages.map { it.id })
        }

    @Test
    fun unknownProtocol_probesBeforeRoutingToV2() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondMessagesOrEmptyInbox(
                        request,
                        """{"data":[{"id":"msg_v2","type":"user","text":"V2","time":{"created":1}}],"cursor":{"next":null}}""",
                    )
                }
            val manager = manager(null, ConnectionProbing { ServerProtocol.V2 })
            val router =
                ProtocolSelectedMessageRemote(
                    api = MessagesApi(client, json),
                    adapter = MessageDomainAdapter(),
                    activeConnectionManager = manager,
                )

            val messages = router.listMessages("ses_2", "/repo")

            assertEquals("msg_v2", messages.single().id)
            assertTrue(
                requests
                    .messageRequests()
                    .single()
                    .url.encodedPath
                    .contains("/api/session/ses_2/message"),
            )
        }

    private suspend fun router(
        client: HttpClient,
        protocol: ServerProtocol,
    ): ProtocolSelectedMessageRemote =
        ProtocolSelectedMessageRemote(
            api = MessagesApi(client, json),
            adapter = MessageDomainAdapter(),
            activeConnectionManager = manager(protocol),
        )

    private suspend fun manager(
        protocol: ServerProtocol?,
        probing: ConnectionProbing? = null,
    ): ActiveConnectionManager {
        val manager = ActiveConnectionManager(FakeProfileStore(), probing)
        manager.applyProfile(
            ConnectionProfile(baseUrl = "http://127.0.0.1:4096/", username = "opencode", password = ""),
            serverProtocol = protocol,
        )
        return manager
    }

    private fun testClient(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(HttpTimeout)
            install(ContentNegotiation) { json(json) }
        }

    private val json = Json { ignoreUnknownKeys = true }
}

private fun MockRequestHandleScope.respondJson(content: String) =
    respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

private fun MockRequestHandleScope.respondMessagesOrEmptyInbox(
    request: HttpRequestData,
    messagesJson: String,
) = if (request.url.encodedPath.contains("/inbox")) {
    respondJson("""{"data":[]}""")
} else {
    respondJson(messagesJson)
}

private fun List<HttpRequestData>.messageRequests(): List<HttpRequestData> = filter { !it.url.encodedPath.contains("/inbox") }

private class FakeProfileStore : ConnectionProfileStore {
    private var profile: ConnectionProfile? = null

    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        this.profile = profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {}

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {}

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {}

    override suspend fun clearAll() {
        profile = null
    }
}
