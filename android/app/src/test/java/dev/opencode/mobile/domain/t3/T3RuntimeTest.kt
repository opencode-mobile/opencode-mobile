package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.session.SessionTodoStatus
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.markdown.OpenCodeSyntaxHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class T3RuntimeTest {
    /** Background-scope work only runs through runCurrent; cover one backoff/debounce window too. */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
    }

    private class Harness(
        scope: TestScope,
        profile: FakeProfileStore = FakeProfileStore(t3Profile),
    ) {
        val store = profile
        val http = FakeT3Http()
        val socket = FakeT3Socket()
        val events = RecordingEventStream()
        lateinit var manager: ActiveConnectionManager
        lateinit var runtime: T3Runtime
        private val testScope = scope

        suspend fun build(): Harness {
            manager = activeManager(store)
            runtime =
                T3Runtime(
                    api = T3Api(http),
                    socket = socket,
                    activeConnectionManager = manager,
                    eventStream = events,
                    scope = testScope.backgroundScope,
                    shellRefreshDebounceMs = 10,
                )
            return this
        }
    }

    @Test
    fun reconnectResubscribesObservedThreadFromLastSequence() =
        runTest {
            val h = Harness(this).build()
            h.http.threadJson["thr-1"] = threadSnapshotJson(sequence = 5, "m1")

            h.runtime.loadThreadMessages("thr-1")
            settle()

            assertEquals(1, h.socket.connectCalls)
            val firstShell = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_SHELL)
            assertNull("no shell cached yet: ask for a snapshot", firstShell.afterSequence)
            val firstThread = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_THREAD)
            assertEquals("thr-1", firstThread.threadId)
            assertEquals(5L, firstThread.afterSequence)

            // A live event advances the resume cursor past the REST snapshot.
            h.socket.chunk(firstThread.id, threadEventItem(7, messageUpdatedEvent("m2", "reply")))
            settle()
            assertEquals(listOf("m1", "m2"), h.runtime.cachedMessages("thr-1").map { it.id })

            h.socket.drop()
            settle()
            h.runtime.refreshShell()
            settle()

            assertEquals(2, h.socket.connectCalls)
            assertEquals(2, h.socket.subscribes(T3RpcCodec.SUBSCRIBE_SHELL).size)
            val resubscribed = h.socket.subscribes(T3RpcCodec.SUBSCRIBE_THREAD)
            assertEquals(2, resubscribed.size)
            assertEquals("thr-1", resubscribed.last().threadId)
            assertEquals(7L, resubscribed.last().afterSequence)

            // Chunks on the old subscription id are ignored; the new id is live.
            h.socket.chunk(firstThread.id, threadEventItem(8, messageUpdatedEvent("stale", "old socket")))
            h.socket.chunk(resubscribed.last().id, threadEventItem(8, messageUpdatedEvent("m3", "new socket")))
            settle()
            assertEquals(listOf("m1", "m2", "m3"), h.runtime.cachedMessages("thr-1").map { it.id })
        }

    @Test
    fun shellStreamPublishesSyntheticHomeEvents() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellJson(threads = listOf(threadJson("A")), sequence = 3)

            h.runtime.refreshShell()
            settle()
            val shellSub = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_SHELL)
            assertEquals(3L, shellSub.afterSequence)
            assertTrue("REST refresh publishes no lifecycle events", h.events.published.isEmpty())

            h.socket.chunk(shellSub.id, threadUpdatedItem(4, threadJson("B", activeRunId = "run-1")))
            settle()
            assertEquals(listOf("session.created:B", "session.status:B"), h.events.types())
            assertEquals("running", h.events.published.last().rawProperties?.get("type")?.toString()?.trim('"'))

            h.events.published.clear()
            h.socket.chunk(shellSub.id, threadUpdatedItem(5, threadJson("A", pendingRequestId = "req-9")))
            settle()
            assertEquals(listOf("session.updated:A", "permission.asked:A"), h.events.types())

            h.events.published.clear()
            h.socket.chunk(shellSub.id, threadUpdatedItem(2, threadJson("A", title = "stale")))
            settle()
            assertTrue("stale sequence is ignored", h.events.published.isEmpty())
            assertEquals("Thread A", h.runtime.shell.value!!.threads.first { it.id == "A" }.title)

            h.socket.chunk(shellSub.id, threadRemovedItem(6, "B"))
            settle()
            assertEquals(listOf("session.deleted:B", "session.status:B"), h.events.types())
            assertEquals(listOf("A"), h.runtime.shell.value!!.threads.map { it.id })
            assertEquals(6L, h.runtime.shell.value!!.snapshotSequence)
        }

    @Test
    fun archivedShellStaysSeparateFromTheActiveSnapshot() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellJson(threads = listOf(threadJson("A")), sequence = 3)

            h.runtime.refreshShell()
            settle()
            val activeSub = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_SHELL)
            val archiveSub = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_ARCHIVED_SHELL)

            h.socket.chunk(
                archiveSub.id,
                archivedSnapshotItem(listOf(threadJson("Z", archivedAt = "2026-10-02T00:00:00Z")), sequence = 3),
            )
            settle()
            assertEquals(listOf("A"), h.runtime.shell.value!!.threads.map { it.id })
            assertEquals(listOf("Z"), h.runtime.shell.value!!.archivedThreads.map { it.id })
            assertEquals("Z", h.runtime.thread("Z")?.id)

            h.socket.chunk(activeSub.id, shellSnapshotItem(listOf(threadJson("A"), threadJson("B")), sequence = 4))
            settle()
            assertEquals(listOf("A", "B"), h.runtime.shell.value!!.threads.map { it.id })
            assertEquals("an active snapshot does not clear the archive", listOf("Z"), h.runtime.shell.value!!.archivedThreads.map { it.id })

            h.socket.chunk(activeSub.id, threadRemovedItem(5, "A"))
            settle()
            assertEquals(listOf("Z"), h.runtime.shell.value!!.archivedThreads.map { it.id })

            h.socket.chunk(
                archiveSub.id,
                archivedThreadUpdatedItem(6, threadJson("B", archivedAt = "2026-10-02T00:00:00Z")),
            )
            settle()
            assertEquals(emptyList<String>(), h.runtime.shell.value!!.threads.map { it.id })
            assertEquals(listOf("Z", "B"), h.runtime.shell.value!!.archivedThreads.map { it.id })
            assertEquals("the archive cursor does not move the active shell sequence", 5L, h.runtime.shell.value!!.snapshotSequence)
        }

    @Test
    fun stopOnProfileSwitchDisconnectsAndClearsCaches() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellJson(threads = listOf(threadJson("A")), sequence = 3)
            h.http.threadJson["A"] = threadSnapshotJson(sequence = 3, "m1")

            h.runtime.start()
            settle()
            assertEquals(1, h.socket.connectCalls)
            h.runtime.loadThreadMessages("A")
            settle()
            assertEquals(1, h.runtime.cachedMessages("A").size)

            // Simulate the coordinator: stop, swap the active profile to OpenCode, start.
            h.runtime.stop()
            h.store.profile = opencodeProfile
            h.manager.reloadFromStore()
            h.runtime.start()
            settle()

            assertEquals(1, h.socket.disconnectCalls)
            assertEquals(T3SocketState.Disconnected, h.socket.state.value)
            assertEquals(1, h.socket.connectCalls)
            assertNull(h.runtime.shell.value)
            assertTrue(h.runtime.cachedMessages("A").isEmpty())
        }

    @Test
    fun foregroundStartReconnectsAfterDrop() =
        runTest {
            val h = Harness(this).build()
            h.runtime.start()
            settle()
            assertEquals(1, h.socket.connectCalls)

            h.socket.drop()
            settle()
            assertEquals("reconnects with backoff while live", 2, h.socket.connectCalls)
            assertEquals(2, h.socket.subscribes(T3RpcCodec.SUBSCRIBE_SHELL).size)

            h.runtime.stop()
            h.socket.drop()
            settle()
            assertEquals("no reconnect once stopped", 2, h.socket.connectCalls)
        }

    @Test
    fun subscribeFailureSurfacesAsLoadFailed() =
        runTest {
            val h = Harness(this).build()
            h.http.threadJson["gone"] = threadSnapshotJson(sequence = 1)
            val failures = mutableListOf<T3ThreadFailure>()
            backgroundScope.launch { h.runtime.threadFailures.toList(failures) }

            h.runtime.loadThreadMessages("gone")
            settle()
            val sub = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_THREAD)
            h.socket.exitFailure(sub.id, "ThreadNotFound")
            settle()

            assertEquals(listOf("gone"), failures.map { it.threadId })
            val refused = failures.single().error as T3RpcException
            assertEquals("the user sees a short message", T3RpcException.REJECTED_MESSAGE, refused.message)
            assertEquals("the server cause stays available for diagnostics", "ThreadNotFound", refused.detail)

            val preparer =
                MessageContentPreparer(OpenCodeTextMateHighlightingEngine(OpenCodeSyntaxHighlighter.default)).apply {
                    computationDispatcher = Dispatchers.Unconfined
                }
            val repository =
                T3MessageRepository(
                    h.runtime,
                    preparer,
                    T3AttachmentUploader(h.socket, T3AttachmentTransport { _, _, _, _ -> 204 }),
                )
            backgroundScope.launch {
                val update = repository.observeMessages("gone", null).first { it is MessageListUpdate.LoadFailed }
                failures += T3ThreadFailure("flow", (update as MessageListUpdate.LoadFailed).error)
            }
            settle()
            h.socket.exitFailure(h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_THREAD).id, "ThreadNotFound")
            settle()
            assertEquals("the repository flow surfaced it as LoadFailed", "flow", failures.last().threadId)
        }

    @Test
    fun threadEventsCoalesceIntoOneShellRefresh() =
        runTest {
            val h = Harness(this).build()
            h.http.threadJson["A"] = threadSnapshotJson(sequence = 1)
            h.runtime.loadThreadMessages("A")
            settle()
            val sub = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_THREAD)
            val before = h.http.shellRequests

            repeat(5) { i ->
                h.socket.chunk(
                    sub.id,
                    threadEventItem(
                        (10 + i).toLong(),
                        buildJsonObject { put("type", "run.updated") },
                    ),
                )
            }
            settle()
            assertEquals(1, h.http.shellRequests - before)
        }

    @Test
    fun firstShellSnapshotPublishesStatusAndRequestsButNoLifecycleBurst() =
        runTest {
            val h = Harness(this).build()
            h.runtime.start()
            settle()
            val shellSub = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_SHELL)
            assertNull(h.runtime.shell.value)

            h.socket.chunk(
                shellSub.id,
                shellSnapshotItem(
                    threads =
                        listOf(
                            threadJson("A"),
                            threadJson("B", activeRunId = "run-1"),
                            threadJson("C", pendingRequestId = "q-1", pendingRequestKind = "user_input"),
                        ),
                    sequence = 3,
                ),
            )
            settle()
            assertEquals(
                "no session.created burst for threads that already existed",
                listOf("session.status:B", "question.asked:C"),
                h.events.types(),
            )
            assertEquals(listOf("A", "B", "C"), h.runtime.shell.value!!.threads.map { it.id }.sorted())

            // Once a snapshot is cached, lifecycle is diffed as usual.
            h.events.published.clear()
            h.socket.chunk(shellSub.id, threadUpdatedItem(4, threadJson("D")))
            settle()
            assertEquals(listOf("session.created:D"), h.events.types())
        }

    @Test
    fun protocolIsValidatedOncePerProfileOnFreshSockets() =
        runTest {
            val h = Harness(this).build()
            h.runtime.start()
            settle()
            assertEquals(1, h.socket.connectCalls)
            assertEquals("descriptor fetched before the first socket", 1, h.http.environmentRequests)

            h.socket.drop()
            settle()
            assertEquals(2, h.socket.connectCalls)
            assertEquals("a passed profile is not re-fetched this session", 1, h.http.environmentRequests)
        }

    @Test
    fun protocolMismatchOnConnectStopsRetryingAndSurfacesError() =
        runTest {
            val h = Harness(this).build()
            h.http.threadJson["A"] = threadSnapshotJson(sequence = 1, "m1")
            h.http.environmentJson = environmentJson(protocolVersion = 1)
            h.runtime.start()
            settle()
            assertEquals("validation failed before any socket was opened", 0, h.socket.connectCalls)

            // Several backoff windows later the loop has not retried and the descriptor was fetched once.
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(0, h.socket.connectCalls)
            assertEquals("no reconnect loop on a protocol mismatch", 1, h.http.environmentRequests)
            assertEquals(EventStreamConnectionState.AuthFailed, h.events.states.last())

            // REST callers see the same readable error, still without another fetch or socket.
            val error = runCatching { h.runtime.loadThreadMessages("A") }.exceptionOrNull()
            assertTrue(error is T3ProtocolException)
            assertTrue(error!!.message!!.contains("orchestration protocol 1"))
            assertEquals(1, h.http.environmentRequests)
            assertEquals(0, h.socket.connectCalls)
            assertEquals(EventStreamConnectionState.AuthFailed, h.events.states.last())

            // Backgrounding / profile switch forgets the verdict so a re-pair can try again.
            h.runtime.stop()
            h.http.environmentJson = environmentJson(protocolVersion = 2)
            h.runtime.start()
            settle()
            assertEquals(1, h.socket.connectCalls)
            assertEquals(2, h.http.environmentRequests)
            assertEquals(EventStreamConnectionState.Connected, h.events.states.last())
        }

    @Test
    fun protocolMismatchReachesMessageStreamAsLoadFailed() =
        runTest {
            val h = Harness(this).build()
            h.http.threadJson["A"] = threadSnapshotJson(sequence = 1, "m1")
            h.http.environmentJson = environmentJson(protocolVersion = 1)
            val preparer =
                MessageContentPreparer(OpenCodeTextMateHighlightingEngine(OpenCodeSyntaxHighlighter.default)).apply {
                    computationDispatcher = Dispatchers.Unconfined
                }
            val repository =
                T3MessageRepository(
                    h.runtime,
                    preparer,
                    T3AttachmentUploader(h.socket, T3AttachmentTransport { _, _, _, _ -> 204 }),
                )

            val update = repository.observeMessages("A", null).first()
            settle()

            val failed = update as MessageListUpdate.LoadFailed
            assertTrue(failed.error is T3ProtocolException)
            assertEquals(
                "This T3 environment uses orchestration protocol 1; OpenCode Mobile requires 2",
                failed.error.message,
            )
            assertEquals(0, h.socket.connectCalls)
            assertEquals(EventStreamConnectionState.AuthFailed, h.events.states.last())
        }

    @Test
    fun socketDropFailsPendingRequestsWithReadableMessage() =
        runTest {
            val h = Harness(this).build()
            h.runtime.start()
            settle()
            h.socket.drop()
            settle()
            // The fake reconnects immediately; a request on a dead socket is the connection-lost path.
            h.socket.failNextRequest = true
            val error = runCatching { h.runtime.dispatch(buildJsonObject { put("type", "noop") }) }.exceptionOrNull()
            assertTrue(error is T3ConnectionLostException)
            assertEquals(T3ConnectionLostException.USER_MESSAGE, error!!.message)
        }

    @Test
    fun dispatchRejectionLogsServerCauseToDiagnostics() =
        runTest {
            DiagnosticLogBuffer.resetSharedForTests()
            val h = Harness(this).build()
            h.runtime.start()
            settle()
            h.socket.rejectNextRequestWith = JsonPrimitive("SchemaError: Missing key creationSource")
            val error =
                runCatching {
                    h.runtime.dispatch(buildJsonObject { put("type", "message.dispatch") })
                }.exceptionOrNull()
            assertEquals(T3RpcException.REJECTED_MESSAGE, error!!.message)
            val logged =
                DiagnosticLogBuffer.shared
                    .snapshot()
                    .entries
                    .map { it.message }
            assertTrue(
                logged.toString(),
                logged.any {
                    it.contains("T3 dispatch failed type=message.dispatch") &&
                        it.contains("SchemaError: Missing key creationSource")
                },
            )
            DiagnosticLogBuffer.resetSharedForTests()
        }

    @Test
    fun sendPromptWithoutModelSelectionFailsBeforeDispatch() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson =
                shellJson(
                    threads =
                        listOf(
                            buildJsonObject {
                                put("id", "thr-1")
                                put("title", "Thread")
                                put("projectId", "proj-1")
                                put("status", "idle")
                                put("providerInstanceId", "codex-1")
                            },
                        ),
                )
            val repository = messages(h)

            val error =
                runCatching {
                    repository.sendPromptAsync("thr-1", PromptSendOptions(text = "hi"), null)
                }.exceptionOrNull()

            assertTrue(error is MessageError.Http)
            assertEquals("This thread has no model selection.", error!!.message)
            assertTrue(h.socket.requests.none { it.tag == T3RpcCodec.DISPATCH_COMMAND })
        }

    @Test
    fun sendPrompt_seedsTitleUntilTheThreadHasMessages() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson =
                shellJson(
                    threads =
                        listOf(
                            buildJsonObject {
                                put("id", "thr-1")
                                put("title", "New thread")
                                put("projectId", "proj-1")
                                put("status", "idle")
                                put(
                                    "modelSelection",
                                    buildJsonObject {
                                        put("instanceId", "codex-1")
                                        put("model", "gpt-5")
                                    },
                                )
                            },
                        ),
                )
            h.http.threadJson["thr-1"] = threadSnapshotJson(sequence = 4, "m1")
            val repository = messages(h)

            repository.sendPromptAsync(
                "thr-1",
                PromptSendOptions(text = "  Fix   the login\nredirect  "),
                null,
            )
            val first = h.socket.requests.last { it.tag == T3RpcCodec.DISPATCH_COMMAND }.payload
            assertEquals("Fix the login redirect", first["titleSeed"]!!.jsonPrimitive.content)

            h.runtime.loadThreadMessages("thr-1")
            settle()
            repository.sendPromptAsync("thr-1", PromptSendOptions(text = "Follow up"), null)
            val second = h.socket.requests.last { it.tag == T3RpcCodec.DISPATCH_COMMAND }.payload
            assertEquals("Follow up", second["text"]!!.jsonPrimitive.content)
            assertFalse(second.containsKey("titleSeed"))
        }

    @Test
    fun clearMessageCacheDropsTranscriptAndResubscribesFromTheStart() =
        runTest {
            val h = Harness(this).build()
            h.http.shellJson = shellJson(threads = listOf(threadJson("thr-1")), sequence = 3)
            h.http.threadJson["thr-1"] = threadSnapshotJson(sequence = 5, "m1")
            val repository = messages(h)

            h.runtime.refreshShell()
            settle()
            h.runtime.loadThreadMessages("thr-1")
            settle()
            assertEquals(listOf("m1"), h.runtime.cachedMessages("thr-1").map { it.id })
            val first = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_THREAD)
            assertEquals(5L, first.afterSequence)

            repository.clearMessageCache()
            settle()

            assertTrue(h.runtime.cachedMessages("thr-1").isEmpty())
            assertTrue(h.runtime.messageLists.value["thr-1"].orEmpty().isEmpty())
            assertEquals(listOf("thr-1"), h.runtime.shell.value!!.threads.map { it.id })
            val again = h.socket.subscribes(T3RpcCodec.SUBSCRIBE_THREAD)
            assertEquals(2, again.size)
            assertEquals("thr-1", again.last().threadId)
            assertNull(again.last().afterSequence)

            h.socket.chunk(first.id, threadEventItem(6, messageUpdatedEvent("m2", "stale")))
            settle()
            assertTrue("a chunk on the dropped subscription does not restore the transcript", h.runtime.cachedMessages("thr-1").isEmpty())

            h.socket.drop()
            settle()
            h.runtime.refreshShell()
            settle()
            val resubscribed = h.socket.subscribes(T3RpcCodec.SUBSCRIBE_THREAD).last()
            assertEquals("thr-1", resubscribed.threadId)
            assertNull(resubscribed.afterSequence)
        }

    @Test
    fun todoListFollowsTheActiveRun() =
        runTest {
            val h = Harness(this).build()
            h.http.threadJson["thr-1"] =
                """
                {
                  "snapshotSequence": 3,
                  "projection": {
                    "thread": {
                      "id": "thr-1",
                      "title": "Tasks",
                      "projectId": "p",
                      "status": "busy",
                      "activeRunId": "run-1",
                      "activityRunStatus": "running"
                    },
                    "messages": [],
                    "runs": [{"id": "run-1", "status": "running", "userMessageId": "m1"}],
                    "plans": [
                      {
                        "id": "plan-old",
                        "threadId": "thr-1",
                        "runId": "run-0",
                        "nodeId": "n",
                        "kind": "todo_list",
                        "status": "completed",
                        "steps": [{"id": "s", "text": "Old", "status": "pending"}]
                      },
                      {
                        "id": "plan-md",
                        "threadId": "thr-1",
                        "runId": "run-1",
                        "nodeId": "n",
                        "kind": "proposed_plan",
                        "status": "active",
                        "markdown": "# Plan"
                      },
                      {
                        "id": "plan-1",
                        "threadId": "thr-1",
                        "runId": "run-1",
                        "nodeId": "n",
                        "kind": "todo_list",
                        "status": "active",
                        "steps": [
                          {"id": "a", "text": "Parse queued runs", "status": "completed"},
                          {"id": "b", "text": "Mark messages waiting", "status": "running"},
                          {"id": "c", "text": "Leave the rest", "status": "pending"}
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent()

            h.runtime.loadThreadMessages("thr-1")
            settle()

            val todos = h.runtime.sessionTodos("thr-1")
            assertEquals(
                listOf("Parse queued runs", "Mark messages waiting", "Leave the rest"),
                todos.map { it.content },
            )
            assertEquals(SessionTodoStatus.Completed, todos[0].status)
            assertEquals(SessionTodoStatus.InProgress, todos[1].status)
            assertEquals(SessionTodoStatus.Pending, todos[2].status)
            assertEquals("thr-1", h.events.published.last { it.type == "todo.updated" }.sessionId)

            val subscription = h.socket.lastSubscribe(T3RpcCodec.SUBSCRIBE_THREAD)
            h.socket.chunk(
                subscription.id,
                threadEventItem(
                    4,
                    buildJsonObject {
                        put("type", "plan.updated")
                        put(
                            "payload",
                            buildJsonObject {
                                put("id", "plan-1")
                                put("kind", "todo_list")
                                put("runId", "run-1")
                                put("status", "active")
                                put(
                                    "steps",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("id", "b")
                                                put("text", "Mark messages waiting")
                                                put("status", "completed")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                ),
            )
            settle()
            val updated = h.runtime.sessionTodos("thr-1")
            assertEquals(listOf("Mark messages waiting"), updated.map { it.content })
            assertEquals(SessionTodoStatus.Completed, updated.single().status)

            h.runtime.clearTranscript()
            settle()
            assertTrue(h.runtime.sessionTodos("thr-1").isEmpty())
            h.http.threadJson["thr-1"] =
                """
                {
                  "snapshotSequence": 6,
                  "projection": {
                    "messages": [],
                    "runs": [{"id": "run-1", "status": "running", "userMessageId": "m1"}],
                    "plans": [
                      {
                        "id": "plan-1",
                        "kind": "todo_list",
                        "runId": "run-1",
                        "status": "active",
                        "steps": [{"id": "b", "text": "Mark messages waiting", "status": "completed"}]
                      }
                    ]
                  }
                }
                """.trimIndent()
            h.runtime.loadThreadMessages("thr-1")
            settle()
            assertTrue(
                "a snapshot that omits the thread does not revive tasks from the cleared run",
                h.runtime.sessionTodos("thr-1").isEmpty(),
            )

            h.http.shellJson =
                shellJson(
                    threads =
                        listOf(
                            buildJsonObject {
                                put("id", "thr-1")
                                put("title", "Tasks")
                                put("projectId", "p")
                                put("status", "idle")
                            },
                        ),
                )
            h.runtime.refreshShell()
            settle()
            assertTrue(h.runtime.sessionTodos("thr-1").isEmpty())
        }

    private fun messages(h: Harness): T3MessageRepository {
        val preparer =
            MessageContentPreparer(OpenCodeTextMateHighlightingEngine(OpenCodeSyntaxHighlighter.default)).apply {
                computationDispatcher = Dispatchers.Unconfined
            }
        return T3MessageRepository(
            h.runtime,
            preparer,
            T3AttachmentUploader(h.socket, T3AttachmentTransport { _, _, _, _ -> 204 }),
        )
    }
}
