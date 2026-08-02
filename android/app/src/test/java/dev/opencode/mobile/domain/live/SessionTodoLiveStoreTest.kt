package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.SessionTodoStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTodoLiveStoreTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun setLive_fetchesTodos() =
        runTest(testDispatcher) {
            val repository =
                FakeTodoSessionRepository(
                    todos = listOf(SessionTodo(0, "A", SessionTodoStatus.Pending, "low")),
                )
            val store = createStore(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<List<SessionTodo>>()
            val job =
                launch {
                    store.observeTodos("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.setLive(true, "ses-1", "/repo")
            advanceUntilIdle()
            job.cancel()

            assertEquals(1, repository.listTodosCallCount)
            assertEquals("A", emissions.last().single().content)
        }

    @Test
    fun todoUpdated_replacesFullList() =
        runTest(testDispatcher) {
            val repository = FakeTodoSessionRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createStore(repository, eventStream)
            val emissions = mutableListOf<List<SessionTodo>>()
            val job =
                launch {
                    store.observeTodos("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()
            store.setLive(true, "ses-1", "/repo")
            advanceUntilIdle()

            eventStream.emit(
                OpencodeEvent(
                    type = "todo.updated",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put(
                                "todos",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("content", "From SSE")
                                            put("status", "in_progress")
                                            put("priority", "high")
                                        },
                                    )
                                },
                            )
                        },
                ),
            )
            advanceUntilIdle()
            job.cancel()

            assertEquals("From SSE", emissions.last().single().content)
            assertEquals(SessionTodoStatus.InProgress, emissions.last().single().status)
        }

    @Test
    fun setLiveFalse_clearsCacheAndForceGetsNextLive() =
        runTest(testDispatcher) {
            val repository =
                FakeTodoSessionRepository(
                    todos = listOf(SessionTodo(0, "A", SessionTodoStatus.Pending, "low")),
                )
            val store = createStore(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<List<SessionTodo>>()
            val job =
                launch {
                    store.observeTodos("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.setLive(true, "ses-1", "/repo")
            advanceUntilIdle()
            assertEquals(1, repository.listTodosCallCount)

            store.setLive(false, "ses-1", "/repo")
            advanceUntilIdle()
            assertTrue(emissions.last().isEmpty())

            repository.todos = listOf(SessionTodo(0, "B", SessionTodoStatus.Pending, "low"))
            store.setLive(true, "ses-1", "/repo")
            advanceUntilIdle()
            job.cancel()

            assertEquals(2, repository.listTodosCallCount)
            assertEquals("B", emissions.last().single().content)
        }

    @Test
    fun todoUpdated_ignoredWhileIdle() =
        runTest(testDispatcher) {
            val repository =
                FakeTodoSessionRepository(
                    todos = listOf(SessionTodo(0, "A", SessionTodoStatus.Pending, "low")),
                )
            val eventStream = FakeForegroundEventStream()
            val store = createStore(repository, eventStream)
            val emissions = mutableListOf<List<SessionTodo>>()
            val job =
                launch {
                    store.observeTodos("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.setLive(true, "ses-1", "/repo")
            advanceUntilIdle()
            store.setLive(false, "ses-1", "/repo")
            advanceUntilIdle()
            assertTrue(emissions.last().isEmpty())

            eventStream.emit(
                OpencodeEvent(
                    type = "todo.updated",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put(
                                "todos",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("content", "Stale")
                                            put("status", "pending")
                                            put("priority", "low")
                                        },
                                    )
                                },
                            )
                        },
                ),
            )
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().isEmpty())
        }

    @Test
    fun ignoresTodoUpdatedForOtherSession() =
        runTest(testDispatcher) {
            val repository = FakeTodoSessionRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createStore(repository, eventStream)
            val emissions = mutableListOf<List<SessionTodo>>()
            val job =
                launch {
                    store.observeTodos("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()
            store.setLive(true, "ses-1", "/repo")
            advanceUntilIdle()
            val before = emissions.size

            eventStream.emit(
                OpencodeEvent(
                    type = "todo.updated",
                    sessionId = "ses-other",
                    rawProperties =
                        buildJsonObject {
                            put(
                                "todos",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("content", "Other")
                                            put("status", "pending")
                                            put("priority", "low")
                                        },
                                    )
                                },
                            )
                        },
                ),
            )
            advanceUntilIdle()
            job.cancel()

            assertEquals(before, emissions.size)
        }

    private fun createStore(
        repository: SessionRepository,
        eventStream: FakeForegroundEventStream,
    ): DefaultSessionTodoLiveStore =
        DefaultSessionTodoLiveStore(
            sessionRepository = repository,
            eventStream = eventStream,
            liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
        )
}

private class FakeTodoSessionRepository(
    var todos: List<SessionTodo> = emptyList(),
    var listTodosCallCount: Int = 0,
) : SessionRepository {
    override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> = emptyList()

    override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> = emptyList()

    override suspend fun listSessions(directory: String?): List<Session> = emptyList()

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ): Session = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> = emptyList()

    override suspend fun getSession(
        id: String,
        directory: String?,
    ): Session = error("unused")

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ): Session = error("unused")

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ): Session = error("unused")

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ): Boolean = error("unused")

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ): List<Session> = emptyList()

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ): List<SessionTodo> {
        listTodosCallCount += 1
        return todos
    }

    override suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): Boolean = error("unused")

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ): Session = error("unused")

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ): Boolean = true

    override suspend fun shareSession(
        id: String,
        directory: String?,
    ): Session = error("unused")

    override suspend fun unshareSession(
        id: String,
        directory: String?,
    ): Session = error("unused")

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ): List<FileDiff> = emptyList()

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ): Boolean = error("unused")

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ): Boolean = error("unused")

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ): Boolean = error("unused")

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
        directory: String?,
    ): Boolean = error("unused")

    override suspend fun replyToQuestion(
        questionId: String,
        answers: List<List<String>>,
        directory: String?,
    ): Boolean = error("unused")

    override suspend fun rejectQuestion(
        questionId: String,
        directory: String?,
    ): Boolean = error("unused")
}
