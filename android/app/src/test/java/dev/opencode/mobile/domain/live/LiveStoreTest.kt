package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageLiveStoreTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun observeMessages_emitsInitialMessages() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val store = createMessageStore(repository, FakeForegroundEventStream())

            val messages = store.observeMessages("ses-1", "/repo").first()

            assertEquals("Hello", messages.single().text)
        }

    @Test
    fun observeMessages_refreshesOnMatchingEventAfterDebounce() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageStore(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceTimeBy(1)

            repository.messages = listOf(Message(id = "msg-2", role = MessageRole.Assistant, text = "Updated"))
            eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals(2, emissions.size)
            assertEquals("Updated", emissions.last().single().text)
        }

    @Test
    fun observeMessages_ignoresEventsForOtherSessions() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageStore(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceTimeBy(1)

            eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-2"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals(1, emissions.size)
            assertEquals("Hello", emissions.single().single().text)
        }

    @Test
    fun observeMessages_pollsTimelineWhenFingerprintChanges() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val store = createMessageStore(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceTimeBy(1)

            repository.messages = listOf(Message(id = "msg-2", role = MessageRole.Assistant, text = "Polled"))
            advanceTimeBy(
                LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS + LiveStoreRefreshPolicy.MESSAGE_DEBOUNCE_MS,
            )
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals(2, emissions.size)
            assertEquals("Polled", emissions.last().single().text)
        }

    @Test
    fun observeMessages_doesNotEmitDuplicateOnUnchangedPoll() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val store = createMessageStore(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceTimeBy(1)

            advanceTimeBy(
                LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS + LiveStoreRefreshPolicy.MESSAGE_DEBOUNCE_MS,
            )
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals(1, emissions.size)
            assertEquals("Hello", emissions.single().single().text)
        }

    @Test
    fun observeMessages_keepsLastValueWhenRefreshFails() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageStore(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceTimeBy(1)

            repository.shouldFailLoad = true
            eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals(1, emissions.size)
            assertEquals("Hello", emissions.single().single().text)
        }

    private fun createMessageStore(
        repository: FakeMessageRepository,
        eventStream: FakeForegroundEventStream,
    ): DefaultMessageLiveStore {
        val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        return DefaultMessageLiveStore(repository, eventStream, scope)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStatusLiveStoreTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun observeStatuses_emitsBulkMap() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val store = createStatusStore(repository, FakeForegroundEventStream())

            val statuses = store.observeStatuses("/repo").first()

            assertEquals("busy", statuses["ses-1"]?.raw?.get("type"))
        }

    @Test
    fun observeStatuses_emitsEmptyMapWhenStatusLoadFails() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(shouldFailStatuses = true)
            val store = createStatusStore(repository, FakeForegroundEventStream())

            val statuses = store.observeStatuses("/repo").first()

            assertEquals(emptyMap<String, SessionStatus>(), statuses)
        }

    @Test
    fun observeStatuses_refreshesOnStatusEvent() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<Map<String, SessionStatus>>()
            val collectJob =
                launch {
                    store.observeStatuses("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            repository.statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "idle")))
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            advanceTimeBy(350)
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(2, emissions.size)
            assertEquals("idle", emissions.last()["ses-1"]?.raw?.get("type"))
        }

    private fun createStatusStore(
        repository: FakeSessionRepository,
        eventStream: FakeForegroundEventStream,
    ): DefaultSessionStatusLiveStore =
        DefaultSessionStatusLiveStore(
            repository,
            eventStream,
            LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLiveStoreTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun observeSessions_refreshesOnSessionCreated() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    sessions = listOf(testSession(id = "ses-1", title = "First")),
                )
            val eventStream = FakeForegroundEventStream()
            val store =
                DefaultSessionLiveStore(
                    repository,
                    eventStream,
                    LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                )
            val emissions = mutableListOf<List<Session>>()
            val collectJob =
                launch {
                    store.observeSessions("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            repository.sessions =
                listOf(
                    testSession(id = "ses-1", title = "First"),
                    testSession(id = "ses-2", title = "Second"),
                )
            eventStream.emit(OpencodeEvent(type = "session.created", sessionId = "ses-2"))
            advanceTimeBy(550)
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(2, emissions.size)
            assertEquals(2, emissions.last().size)
        }

    @Test
    fun observeSessions_keepsLastValueWhenRefreshFails() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    sessions = listOf(testSession(id = "ses-1", title = "First")),
                )
            val eventStream = FakeForegroundEventStream()
            val store =
                DefaultSessionLiveStore(
                    repository,
                    eventStream,
                    LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                )
            val emissions = mutableListOf<List<Session>>()
            val collectJob =
                launch {
                    store.observeSessions("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            repository.shouldFailList = true
            eventStream.emit(OpencodeEvent(type = "session.updated", sessionId = "ses-1"))
            advanceTimeBy(550)
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(1, emissions.size)
            assertEquals("First", emissions.single().single().title)
        }
}

private fun testSession(
    id: String,
    title: String,
): Session =
    Session(
        id = id,
        title = title,
        directory = "/repo",
    )

private class FakeMessageRepository(
    var messages: List<Message> = listOf(Message(id = "msg-1", role = MessageRole.User, text = "Hello")),
    var shouldFailLoad: Boolean = false,
) : MessageRepository {
    var listMessagesCallCount = 0

    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> {
        listMessagesCallCount += 1
        if (shouldFailLoad) error("load failed")
        return messages
    }

    override suspend fun getMessage(
        sessionId: String,
        messageId: String,
        directory: String?,
    ): Message = error("unused")

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        directory: String?,
    ): Message = error("unused")

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) = Unit
}

private class FakeSessionRepository(
    var sessions: List<Session> = emptyList(),
    var statuses: List<SessionStatus> = emptyList(),
    var shouldFailList: Boolean = false,
    var shouldFailStatuses: Boolean = false,
) : SessionRepository {
    override suspend fun listSessions(directory: String?): List<Session> {
        if (shouldFailList) error("list failed")
        return sessions
    }

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ): Session = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> {
        if (shouldFailStatuses) error("status failed")
        return statuses
    }

    override suspend fun getSession(
        id: String,
        directory: String?,
    ): Session = error("unused")

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ): Session = error("unused")

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ): Boolean = error("unused")

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ): List<Session> = error("unused")

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ): List<SessionTodo> = error("unused")

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
    ): List<FileDiff> = error("unused")

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
}
