package dev.opencode.mobile.domain.session

import dev.opencode.mobile.data.remote.ProjectsApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.DefaultProjectRepository
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.network.ActiveConnectionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProtocolRepositoryTest {
    @Test
    fun v1ListSessions_usesFlatArrayAndDirectoryQuery() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """[{"id":"ses_1","title":"One","projectID":"proj","directory":"/repo","time":{"created":1,"updated":2}}]""",
                    )
                }
            val manager = manager(ServerProtocol.V1)
            val repository = DefaultSessionRepository(SessionsApi(client, json), SessionDomainAdapter(), activeConnectionManager = manager)

            val sessions = repository.listSessions("/repo")

            assertEquals("ses_1", sessions.single().id)
            assertEquals("/repo", requests.single().url.parameters["directory"])
            assertTrue(requests.single().url.encodedPath.endsWith("/session"))
        }

    @Test
    fun v2ListSessions_unwrapsEnvelopeAndKeepsDirectoryQuery() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "data":[{
                            "id":"ses_2",
                            "title":"Two",
                            "projectID":"proj",
                            "cost":0,
                            "tokens":{"input":1,"output":2,"reasoning":0,"cache":{"read":0,"write":0}},
                            "time":{"created":1,"updated":2},
                            "location":{"directory":"/repo"}
                          }],
                          "cursor":{"previous":null,"next":null}
                        }
                        """.trimIndent(),
                    )
                }
            val manager = manager(ServerProtocol.V2)
            val repository = DefaultSessionRepository(SessionsApi(client, json), SessionDomainAdapter(), activeConnectionManager = manager)

            val sessions = repository.listSessions("/repo")

            assertEquals("ses_2", sessions.single().id)
            assertEquals("/repo", sessions.single().directory)
            assertEquals("/repo", requests.single().url.parameters["directory"])
            assertTrue(requests.single().url.encodedPath.contains("/api/session"))
        }

    @Test
    fun v2ListSessions_mapsMissingTitleToNewSession() =
        runTest {
            val client =
                testClient {
                    respondJson(
                        """
                        {
                          "data":[
                            {
                              "id":"ses_untitled",
                              "projectID":"global",
                              "cost":0,
                              "tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
                              "time":{"created":1,"updated":1},
                              "location":{"directory":"/home/ivan"}
                            },
                            {
                              "id":"ses_titled",
                              "title":"What is OpenCode 2?",
                              "projectID":"global",
                              "cost":0,
                              "tokens":{"input":1,"output":2,"reasoning":0,"cache":{"read":0,"write":0}},
                              "time":{"created":2,"updated":3},
                              "location":{"directory":"/home/ivan"}
                            }
                          ],
                          "cursor":{"previous":null,"next":null}
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val sessions = repository.listSessions("/home/ivan")

            assertEquals(listOf("ses_untitled", "ses_titled"), sessions.map { it.id })
            assertEquals(listOf("New Session", "What is OpenCode 2?"), sessions.map { it.title })
            assertEquals("/home/ivan", sessions.first().directory)
        }

    @Test
    fun v2ActiveStatuses_mapRunningToBusy() =
        runTest {
            val client =
                testClient {
                    respondJson("""{"data":{"ses_busy":{"type":"running"}},"watermarks":{}}""")
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val statuses = repository.getAllSessionStatuses("/repo")

            assertEquals("busy", statuses.single().raw["type"])
            assertEquals("ses_busy", statuses.single().sessionId)
        }

    @Test
    fun v2Rename_postsRenameThenReloadsSession() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val responses =
                ArrayDeque(
                    listOf(
                        HttpStatusCode.NoContent to "",
                        HttpStatusCode.OK to
                            """{"data":{"id":"ses_1","title":"Renamed","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}}""",
                    ),
                )
            val client =
                testClient { request ->
                    requests += request
                    val (status, body) = responses.removeFirst()
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val session = repository.updateSession("ses_1", title = "Renamed", directory = "/repo")

            assertEquals("Renamed", session.title)
            assertEquals(HttpMethod.Post, requests[0].method)
            assertTrue(requests[0].url.encodedPath.endsWith("/api/session/ses_1/rename"))
            assertTrue(requests[1].url.encodedPath.endsWith("/api/session/ses_1"))
        }

    @Test
    fun v2Fork_postsBoundaryEnvelope() =
        runTest {
            var captured: HttpRequestData? = null
            val client =
                testClient { request ->
                    captured = request
                    respondJson(
                        """{"data":{"id":"ses_fork","title":"Fork","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"},"parentID":"ses_1"}}""",
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val forked = repository.forkSession("ses_1", atMessageId = "msg_1", directory = "/repo")

            assertEquals("ses_fork", forked.id)
            assertEquals("ses_1", forked.parentId)
            assertEquals(HttpMethod.Post, captured?.method)
            assertTrue(captured!!.url.encodedPath.endsWith("/api/session/ses_1/fork"))
            assertTrue(captured.requestBodyText().contains("\"boundary\""))
            assertTrue(captured.requestBodyText().contains("\"type\":\"before\""))
            assertTrue(captured.requestBodyText().contains("\"messageID\":\"msg_1\""))
        }

    @Test
    fun v2ProjectCurrent_usesLocationDirectoryQuery() =
        runTest {
            var captured: HttpRequestData? = null
            val client =
                testClient { request ->
                    captured = request
                    respondJson("""{"id":"proj_1","directory":"/repo"}""")
                }
            val manager = manager(ServerProtocol.V2)
            val repository =
                DefaultProjectRepository(
                    projectsApi = ProjectsApi(client),
                    adapter = ProjectDomainAdapter(),
                    activeConnectionManager = manager,
                )

            val project = repository.getCurrentProject("/repo")

            assertEquals("proj_1", project.id)
            assertEquals("/repo", project.worktree)
            assertEquals("/repo", captured?.url?.parameters?.get("location[directory]"))
            assertNull(captured?.url?.parameters?.get("directory"))
            assertTrue(captured!!.url.encodedPath.contains("/api/project/current"))
        }

    @Test
    fun v2CreateSession_postsTitleAndLocation() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """{"data":{"id":"ses_new","title":"Named","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}}""",
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val created = repository.createSession(parentId = null, title = "Named", directory = "/repo")

            assertEquals("ses_new", created.id)
            assertEquals("Named", created.title)
            assertEquals(1, requests.size)
            assertTrue(requests[0].url.encodedPath.endsWith("/api/session"))
            assertTrue(requests[0].requestBodyText().contains("\"directory\":\"/repo\""))
            assertTrue(requests[0].requestBodyText().contains("\"title\":\"Named\""))
        }

    @Test
    fun v2CreateSession_acceptsUntitledCreateResponse() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """{"data":{"id":"ses_new","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}}""",
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val created = repository.createSession(parentId = null, title = null, directory = "/repo")

            assertEquals("ses_new", created.id)
            assertEquals("New Session", created.title)
            assertEquals("/repo", created.directory)
            assertEquals(1, requests.size)
        }

    @Test
    fun v2CreateSession_acceptsUnwrappedCreateResponse() =
        runTest {
            val client =
                testClient {
                    respondJson(
                        """{"id":"ses_bare","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}""",
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val created = repository.createSession(parentId = null, title = null, directory = "/repo")

            assertEquals("ses_bare", created.id)
            assertEquals("New Session", created.title)
        }

    @Test
    fun v2ListSessions_followsCursorPages() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val pages =
                ArrayDeque(
                    listOf(
                        """{"data":[{"id":"ses_a","title":"A","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}],"cursor":{"previous":null,"next":"page-2"}}""",
                        """{"data":[{"id":"ses_b","title":"B","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":3,"updated":4},"location":{"directory":"/repo"}}],"cursor":{"previous":"page-2","next":null}}""",
                    ),
                )
            val client =
                testClient { request ->
                    requests += request
                    respondJson(pages.removeFirst())
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val sessions = repository.listSessions("/repo")

            assertEquals(listOf("ses_a", "ses_b"), sessions.map { it.id })
            assertEquals(2, requests.size)
            assertEquals(null, requests[0].url.parameters["cursor"])
            assertEquals("page-2", requests[1].url.parameters["cursor"])
        }

    @Test
    fun unknownProtocol_probesBeforeRoutingToV2() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """{"data":[{"id":"ses_2","title":"Two","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}],"cursor":{"next":null}}""",
                    )
                }
            val manager = manager(protocol = null, probing = ConnectionProbing { ServerProtocol.V2 })
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager,
                )

            val sessions = repository.listSessions("/repo")

            assertEquals("ses_2", sessions.single().id)
            assertEquals(ServerProtocol.V2, manager.currentProtocol())
            assertTrue(requests.single().url.encodedPath.contains("/api/session"))
        }

    @Test
    fun v2ListPermissions_unwrapsEnvelopeAndMapsActionToPermission() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {"data":[{
                          "id":"per_1",
                          "sessionID":"ses_1",
                          "action":"edit",
                          "resources":["src/a.kt"],
                          "save":["src/*"],
                          "source":{"type":"tool","messageID":"msg_1","id":"call_1"}
                        }]}
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val permissions = repository.listPendingPermissions("/repo")

            assertEquals("per_1", permissions.single().id)
            assertEquals("edit", permissions.single().permission)
            assertEquals(listOf("src/a.kt"), permissions.single().patterns)
            assertEquals("msg_1", permissions.single().toolMessageId)
            assertEquals("call_1", permissions.single().toolCallId)
            assertTrue(requests.single().url.encodedPath.contains("/api/permission/request"))
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
        }

    @Test
    fun v2RespondToPermission_postsReplyBody() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond("", HttpStatusCode.NoContent)
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val ok = repository.respondToPermission("ses_1", "per_1", PermissionDecision.AllowOnce, "/repo")

            assertTrue(ok)
            assertTrue(requests.single().url.encodedPath.contains("/api/session/ses_1/permission/per_1/reply"))
            assertTrue(requests.single().requestBodyText().contains("\"reply\":\"once\""))
        }

    @Test
    fun v2ListPendingQuestions_fallsBackToFormsWhenQuestionRoute404HasNoContentType() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    when {
                        request.url.encodedPath.contains("/api/question/request") ->
                            respond(
                                content = "",
                                status = HttpStatusCode.NotFound,
                                headers = headersOf(),
                            )
                        request.url.encodedPath.contains("/api/form/request") ->
                            respondJson(
                                """
                                {
                                  "data":[{
                                    "id":"frm_1",
                                    "sessionID":"ses_1",
                                    "title":"Task type",
                                    "fields":[{
                                      "key":"q0",
                                      "title":"Task type",
                                      "description":"What would you like to work on?",
                                      "options":[{"value":"Code changes","label":"Code changes"}],
                                      "custom":true
                                    }]
                                  }]
                                }
                                """.trimIndent(),
                            )
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val questions = repository.listPendingQuestions("/repo")

            assertEquals("frm_1", questions.single().id)
            assertEquals("ses_1", questions.single().sessionId)
            assertEquals("What would you like to work on?", questions.single().questions.single().question)
            assertEquals("Code changes", questions.single().questions.single().options.single().label)
            assertEquals(2, requests.size)
            assertTrue(requests[0].url.encodedPath.contains("/api/question/request"))
            assertTrue(requests[1].url.encodedPath.contains("/api/form/request"))
        }

    @Test
    fun v2ReplyToQuestion_usesSessionScopedPath() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond("", HttpStatusCode.NoContent)
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val ok = repository.replyToQuestion("ses_1", "que_1", listOf(listOf("Yes")), "/repo")

            assertTrue(ok)
            assertTrue(requests.single().url.encodedPath.contains("/api/session/ses_1/question/que_1/reply"))
            assertTrue(requests.single().requestBodyText().contains("\"answers\""))
        }

    @Test
    fun v2AbortSession_postsInterrupt() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond("", HttpStatusCode.NoContent)
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val ok = repository.abortSession("ses_1", "/repo")

            assertTrue(ok)
            assertEquals(HttpMethod.Post, requests.single().method)
            assertTrue(requests.single().url.encodedPath.contains("/api/session/ses_1/interrupt"))
        }

    @Test
    fun v2SummarizeSession_postsCompactWithSteerDelivery() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond("", HttpStatusCode.NoContent)
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val ok = repository.summarizeSession("ses_1", "openai", "gpt-4.1", "/repo")

            assertTrue(ok)
            assertEquals(HttpMethod.Post, requests.single().method)
            assertTrue(requests.single().url.encodedPath.contains("/api/session/ses_1/compact"))
            assertTrue(requests.single().requestBodyText().contains("\"delivery\":\"steer\""))
        }

    @Test
    fun v2RevertSession_stagesWithMessageAndFiles() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond("", HttpStatusCode.NoContent)
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val ok = repository.revertSession("ses_1", "msg_9", "part_2", "/repo")

            assertTrue(ok)
            assertTrue(requests.single().url.encodedPath.contains("/api/session/ses_1/revert/stage"))
            val body = requests.single().requestBodyText()
            assertTrue(body.contains("\"messageID\":\"msg_9\""))
            assertTrue(!body.contains("\"partID\""))
            assertTrue(body.contains("\"files\":true"))
        }

    @Test
    fun v2UnrevertSession_clearsStagedRevert() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respond("", HttpStatusCode.NoContent)
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val ok = repository.unrevertSession("ses_1", "/repo")

            assertTrue(ok)
            assertEquals(HttpMethod.Post, requests.single().method)
            assertTrue(requests.single().url.encodedPath.contains("/api/session/ses_1/revert/clear"))
        }

    @Test
    fun v2ArchiveSession_returnsHttp404WithoutCallingArchive() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("{}")
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val error =
                runCatching { repository.archiveSession("ses_1", "/repo") }.exceptionOrNull() as SessionError.Http

            assertEquals(404, error.statusCode)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun v2ListChildSessions_passesParentId() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """{"data":[{"id":"ses_child","title":"Child","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"},"parentID":"ses_1"}],"cursor":{"next":null}}""",
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val children = repository.listChildSessions("ses_1", "/repo")

            assertEquals("ses_child", children.single().id)
            assertEquals("ses_1", requests.single().url.parameters["parentID"])
        }

    @Test
    fun v2ShareSession_returnsHttp404WithoutCallingLegacyPath() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("{}")
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val error = runCatching { repository.shareSession("ses_1", "/repo") }.exceptionOrNull() as SessionError.Http

            assertEquals(404, error.statusCode)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun v2ListSessionTodos_skipsRestAndSignalsUnavailable() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("[]")
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val error = runCatching { repository.listSessionTodos("ses_1", "/repo") }.exceptionOrNull()

            val http = error as SessionError.Http
            assertEquals(404, http.statusCode)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun v2GetVcsInfo_unwrapsBranchEnvelope() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "location":{"directory":"/repo","project":{"id":"p","directory":"/repo","canonical":"/repo"}},
                          "data":{"branch":{"current":"feature/review","default":"main"}}
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val info = repository.getVcsInfo("/repo")

            assertEquals(VcsInfo("feature/review", "main"), info)
            assertTrue(requests.single().url.encodedPath.contains("/api/vcs"))
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
        }

    @Test
    fun v2GetVcsDiff_mapsGitModeToWorking() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "location":{"directory":"/repo","project":{"id":"p","directory":"/repo","canonical":"/repo"}},
                          "data":[{"file":"App.kt","patch":"@@","additions":1,"deletions":0,"status":"modified"}]
                        }
                        """.trimIndent(),
                    )
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val files = repository.getVcsDiff(VcsDiffMode.WorkingTree, "/repo", context = 12)

            assertEquals("App.kt", files.single().path)
            assertTrue(requests.single().url.encodedPath.contains("/api/vcs/diff"))
            assertEquals("working", requests.single().url.parameters["mode"])
            assertEquals("12", requests.single().url.parameters["context"])
            assertEquals("/repo", requests.single().url.parameters["location[directory]"])
        }

    @Test
    fun v2GetSessionDiff_doesNotCallLegacyPath() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson("[]")
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            try {
                repository.getSessionDiff("ses-1", "msg-1", "/repo")
                org.junit.Assert.fail("expected session message diff to be unavailable on v2")
            } catch (error: SessionError.Http) {
                assertEquals(404, error.statusCode)
            }
            assertTrue(requests.isEmpty())
        }

    @Test
    fun v2Abort_mapsHttpFailureToSessionErrorHttp() =
        runTest {
            val client =
                testClient {
                    respond("", HttpStatusCode.Unauthorized)
                }
            val repository =
                DefaultSessionRepository(
                    SessionsApi(client, json),
                    SessionDomainAdapter(),
                    activeConnectionManager = manager(ServerProtocol.V2),
                )

            val error = runCatching { repository.abortSession("ses_1", "/repo") }.exceptionOrNull()

            val http = error as SessionError.Http
            assertEquals(401, http.statusCode)
        }

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
            install(ContentNegotiation) { json(json) }
        }

    private val json = Json { ignoreUnknownKeys = true }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(content: String) =
    respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

private fun HttpRequestData.requestBodyText(): String =
    (body as? io.ktor.http.content.TextContent)?.text
        ?: body.toString()

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
