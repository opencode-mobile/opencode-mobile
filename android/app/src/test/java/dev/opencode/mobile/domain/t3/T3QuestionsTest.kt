package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.session.QuestionInfo
import dev.opencode.mobile.domain.session.QuestionOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class T3QuestionsTest {
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
    }

    private class Harness(
        scope: TestScope,
    ) {
        val http = FakeT3Http()
        val socket = FakeT3Socket()
        val events = RecordingEventStream()
        lateinit var runtime: T3Runtime
        lateinit var repository: T3SessionRepository
        private val testScope = scope

        suspend fun build(): Harness {
            val api = T3Api(http)
            runtime =
                T3Runtime(
                    api = api,
                    socket = socket,
                    activeConnectionManager = activeManager(FakeProfileStore(t3Profile)),
                    eventStream = events,
                    scope = testScope.backgroundScope,
                    shellRefreshDebounceMs = 10,
                )
            repository = T3SessionRepository(runtime, api)
            return this
        }
    }

    // region fixtures

    private fun pendingThread(
        id: String,
        kind: String,
        requestId: String = "req-$id",
        projectId: String = "proj-a",
        worktreePath: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("title", "Thread $id")
            put("projectId", projectId)
            put("status", "idle")
            if (worktreePath != null) put("worktreePath", worktreePath)
            put(
                "pendingRuntimeRequest",
                buildJsonObject {
                    put("id", requestId)
                    put("kind", kind)
                    put("createdAt", "2026-10-04T10:00:00Z")
                },
            )
        }

    private fun project(
        id: String,
        root: String,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("title", id)
            put("workspaceRoot", root)
        }

    private fun questionItemJson(requestId: String): String =
        """
        {
          "id": "ti-q",
          "type": "user_input_request",
          "ordinal": 4,
          "status": "pending",
          "title": null,
          "requestId": "$requestId",
          "questions": [
            {
              "id": "q-framework",
              "header": "Framework",
              "question": "Which framework should the new screen use?",
              "options": [
                { "label": "SwiftUI", "description": "Declarative UI" },
                { "label": "UIKit", "description": "Imperative UI", "value": "uikit" }
              ]
            },
            {
              "id": "q-targets",
              "header": "Targets",
              "question": "Which platforms?",
              "multiSelect": true,
              "allowCustomAnswer": false,
              "options": [
                { "label": "iOS", "description": "" },
                { "label": "Android", "description": "" }
              ]
            }
          ]
        }
        """.trimIndent()

    private fun threadDetail(vararg items: String): String =
        """
        {
          "snapshotSequence": 9,
          "projection": {
            "messages": [],
            "runtimeRequests": [
              { "id": "req-x", "kind": "user_input", "status": "pending" }
            ],
            "visibleTurnItems": [${items.joinToString(",") { "{\"item\": $it}" }}]
          }
        }
        """.trimIndent()

    // endregion

    @Test
    fun threadSnapshot_parsesQuestionsAndRuntimeRequests() {
        val snapshot = T3Json.threadSnapshot(threadDetail(questionItemJson("req-x")))

        assertEquals(listOf(T3RuntimeRequest("req-x", "user_input", "pending")), snapshot.runtimeRequests)
        val item = snapshot.turnItems.single()
        assertEquals("user_input_request", item.type)
        assertEquals("req-x", item.requestId)
        assertEquals(2, item.questions.size)
        val first = item.questions[0]
        assertEquals("q-framework", first.id)
        assertEquals("Framework", first.header)
        assertFalse(first.multiSelect)
        assertTrue(first.allowCustomAnswer)
        assertEquals("uikit", first.options[1].value)
        assertNull(first.options[0].value)
        val second = item.questions[1]
        assertTrue(second.multiSelect)
        assertFalse(second.allowCustomAnswer)
    }

    @Test
    fun questionRequest_mapsToDomainWithFieldKeys() {
        val thread = T3Json.thread(pendingThread("thr-1", "user_input", requestId = "req-x"))!!
        val items = T3Json.threadSnapshot(threadDetail(questionItemJson("req-x"))).turnItems

        val request = T3SessionMapper.toQuestionRequest(thread, items)!!

        assertEquals("req-x", request.id)
        assertEquals("thr-1", request.sessionId)
        assertNull(request.toolMessageId)
        val first = request.questions[0]
        assertEquals("q-framework", first.fieldKey)
        assertEquals("Framework", first.header)
        assertEquals("Which framework should the new screen use?", first.question)
        assertEquals(listOf("SwiftUI", "UIKit"), first.options.map { it.label })
        assertEquals(listOf("SwiftUI", "uikit"), first.options.map { it.value })
        assertEquals("Declarative UI", first.options[0].description)
        assertFalse(first.multiple)
        assertTrue(first.custom)
        val second = request.questions[1]
        assertTrue(second.multiple)
        assertFalse(second.custom)
    }

    @Test
    fun questionRequest_isNullForApprovalsAndMissingItems() {
        val approval = T3Json.thread(pendingThread("thr-1", "command"))!!
        assertNull(T3SessionMapper.toQuestionRequest(approval, T3Json.threadSnapshot(threadDetail(questionItemJson("req-thr-1"))).turnItems))
        assertNull(T3SessionMapper.approvalId(T3Json.thread(pendingThread("thr-2", "user_input"))!!))

        val question = T3Json.thread(pendingThread("thr-3", "user_input"))!!
        assertNull(T3SessionMapper.toQuestionRequest(question, emptyList()))
    }

    @Test
    fun encodeAnswers_usesQuestionIdsAndOptionValues() {
        val questions =
            listOf(
                QuestionInfo(
                    question = "Framework?",
                    header = "Framework",
                    options = listOf(QuestionOption("SwiftUI", ""), QuestionOption("UIKit", "", value = "uikit")),
                    fieldKey = "q-framework",
                ),
                QuestionInfo(
                    question = "Targets?",
                    header = "Targets",
                    options = listOf(QuestionOption("iOS", ""), QuestionOption("Android", "")),
                    multiple = true,
                    custom = false,
                    fieldKey = "q-targets",
                ),
                QuestionInfo(question = "Notes?", header = "Notes", options = emptyList(), fieldKey = "q-notes"),
            )

        val encoded =
            T3QuestionCommands.encodeAnswers(
                questions,
                listOf(listOf("UIKit"), listOf("iOS", "Android"), listOf("Ship it next week")),
            )

        assertEquals("uikit", encoded["q-framework"]!!.jsonPrimitive.content)
        assertEquals(listOf("iOS", "Android"), encoded["q-targets"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Ship it next week", encoded["q-notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun respondAndDismissCommands_matchTheT3WireShape() {
        val answers = buildJsonObject { put("q-1", "yes") }
        val respond = T3QuestionCommands.userInputRespond("thr-1", "req-1", answers, commandId = "cmd-1")
        assertEquals("runtime-request.respond", respond["type"]!!.jsonPrimitive.content)
        assertEquals("cmd-1", respond["commandId"]!!.jsonPrimitive.content)
        assertEquals("thr-1", respond["threadId"]!!.jsonPrimitive.content)
        assertEquals("req-1", respond["requestId"]!!.jsonPrimitive.content)
        assertEquals(answers, respond["answers"])
        assertNull("a question reply carries no approval decision", respond["decision"])

        val dismiss = T3QuestionCommands.userInputDismiss("thr-1", "req-1", commandId = "cmd-2")
        assertEquals("thread.user-input.dismiss", dismiss["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("type", "commandId", "threadId", "requestId"), dismiss.keys)
    }

    @Test
    fun permission_carriesApprovalPromptAndKind() {
        val thread = T3Json.thread(pendingThread("thr-1", "command", requestId = "req-cmd"))!!
        val items =
            T3Json.threadSnapshot(
                threadDetail(
                    """{"id":"ti-a","type":"approval_request","ordinal":2,"status":"pending","title":"Run tests",
                       "requestId":"req-cmd","requestKind":"command","prompt":"./gradlew test"}""",
                ),
            ).turnItems

        val permission = T3SessionMapper.toPermission(thread, items)!!

        assertEquals("command", permission.permission)
        assertEquals(listOf("./gradlew test"), permission.patterns)
        assertEquals("./gradlew test", permission.metadata["command"]!!.jsonPrimitive.content)
        assertEquals("Run tests", permission.metadata["title"]!!.jsonPrimitive.content)
        assertEquals("command", permission.metadata["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun permission_fileChangeExposesFilePathForTheFileCard() {
        val thread = T3Json.thread(pendingThread("thr-1", "file-change", requestId = "req-edit"))!!
        val items =
            T3Json.threadSnapshot(
                threadDetail(
                    """{"id":"ti-a","type":"approval_request","ordinal":2,"status":"pending","title":null,
                       "requestId":"req-edit","requestKind":"file-change","prompt":"src/App.kt"}""",
                ),
            ).turnItems

        val permission = T3SessionMapper.toPermission(thread, items)!!

        assertEquals("file_change", permission.permission)
        assertEquals(listOf("src/App.kt"), permission.fileChanges.map { it.path })
    }

    @Test
    fun permission_dynamicToolFallsBackToThePendingToolItem() {
        val thread = T3Json.thread(pendingThread("thr-1", "dynamic_tool_call", requestId = "req-tool"))!!
        val items =
            T3Json.threadSnapshot(
                threadDetail(
                    """{"id":"ti-old","type":"dynamic_tool","ordinal":1,"status":"completed","title":null,"toolName":"grep","input":"old"}""",
                    """{"id":"ti-new","type":"dynamic_tool","ordinal":2,"status":"inProgress","title":"Fetch docs","toolName":"webfetch","input":"https://example.com"}""",
                ),
            ).turnItems

        val permission = T3SessionMapper.toPermission(thread, items)!!

        assertEquals("webfetch", permission.permission)
        assertEquals(listOf("https://example.com"), permission.patterns)
        assertEquals("webfetch", permission.metadata["tool"]!!.jsonPrimitive.content)
        assertEquals("Fetch docs", permission.metadata["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun permission_withoutTurnItemsStillNamesTheKind() {
        val thread = T3Json.thread(pendingThread("thr-1", "file-read"))!!
        val permission = T3SessionMapper.toPermission(thread)!!
        assertEquals("file_read", permission.permission)
        assertTrue(permission.patterns.isEmpty())
    }

    @Test
    fun listPendingQuestions_filtersByDirectoryAndLoadsTheThread() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson =
                T3Json.parser.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("snapshotSequence", 1)
                        put(
                            "threads",
                            JsonArray(
                                listOf(
                                    pendingThread("thr-a", "user_input", requestId = "req-a", projectId = "proj-a"),
                                    pendingThread("thr-b", "user_input", requestId = "req-b", projectId = "proj-b", worktreePath = "/repos/b/wt"),
                                    pendingThread("thr-c", "command", requestId = "req-c", projectId = "proj-a"),
                                ),
                            ),
                        )
                        put("projects", JsonArray(listOf(project("proj-a", "/repos/a"), project("proj-b", "/repos/b"))))
                    },
                )
            h.http.threadJson["thr-a"] = threadDetail(questionItemJson("req-a"))
            h.http.threadJson["thr-b"] = threadDetail(questionItemJson("req-b"))

            val forA = h.repository.listPendingQuestions("/repos/a")
            assertEquals(listOf("req-a"), forA.map { it.id })
            assertEquals("thr-a", forA.single().sessionId)
            assertEquals("q-framework", forA.single().questions.first().fieldKey)

            val forWorktree = h.repository.listPendingQuestions("/repos/b/wt")
            assertEquals(listOf("req-b"), forWorktree.map { it.id })

            val all = h.repository.listPendingQuestions(null)
            assertEquals(listOf("req-a", "req-b"), all.map { it.id })

            // The approval thread shows up as a permission, not a question.
            val permissions = h.repository.listPendingPermissions("/repos/a")
            assertEquals(listOf("req-c"), permissions.map { it.id })
        }

    @Test
    fun replyToQuestion_dispatchesAnswersAndRejectDispatchesDismiss() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson =
                T3Json.parser.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("snapshotSequence", 1)
                        put("threads", JsonArray(listOf(pendingThread("thr-a", "user_input", requestId = "req-a"))))
                        put("projects", JsonArray(listOf(project("proj-a", "/repos/a"))))
                    },
                )
            h.http.threadJson["thr-a"] = threadDetail(questionItemJson("req-a"))

            // No question list passed: the repository loads the thread to find the question ids.
            assertTrue(h.repository.replyToQuestion("thr-a", "req-a", listOf(listOf("UIKit"), listOf("iOS")), "/repos/a"))
            settle()
            val respond = h.socket.requests.last { it.tag == T3RpcCodec.DISPATCH_COMMAND }
            assertEquals("runtime-request.respond", respond.payload["type"]!!.jsonPrimitive.content)
            assertEquals("thr-a", respond.threadId)
            val answers = respond.payload["answers"] as JsonObject
            assertEquals("uikit", answers["q-framework"]!!.jsonPrimitive.content)
            assertEquals(JsonArray(listOf(JsonPrimitive("iOS"))), answers["q-targets"])

            // A stale request id is refused without a dispatch.
            val before = h.socket.requests.size
            assertFalse(h.repository.replyToQuestion("thr-a", "req-old", listOf(listOf("UIKit")), "/repos/a"))
            assertEquals(before, h.socket.requests.size)

            // Rejecting without a session id finds the thread by its pending request.
            assertTrue(h.repository.rejectQuestion(requestId = "req-a", directory = "/repos/a"))
            settle()
            val dismiss = h.socket.requests.last { it.tag == T3RpcCodec.DISPATCH_COMMAND }
            assertEquals("thread.user-input.dismiss", dismiss.payload["type"]!!.jsonPrimitive.content)
            assertEquals("req-a", dismiss.payload["requestId"]!!.jsonPrimitive.content)
            assertEquals("thr-a", dismiss.threadId)
        }

    @Test
    fun shellStream_publishesQuestionAskedAndReplied() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellJson(threads = listOf(threadJson("A")), sequence = 3)
            h.runtime.refreshShell()
            settle()
            val shellSub = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_SHELL)

            h.socket.chunk(shellSub.id, threadUpdatedItem(4, pendingThread("A", "user_input", requestId = "req-q", projectId = "proj-1")))
            settle()
            assertEquals(listOf("session.updated:A", "question.asked:A"), h.events.types())

            h.events.published.clear()
            h.socket.chunk(shellSub.id, threadUpdatedItem(5, threadJson("A")))
            settle()
            assertEquals(listOf("session.updated:A", "question.replied:A"), h.events.types())
        }
}
