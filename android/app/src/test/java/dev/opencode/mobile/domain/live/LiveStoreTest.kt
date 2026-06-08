package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun observeMessages_doesNotEmitEmptyBeforeInitialLoadCompletes() =
        runTest(testDispatcher) {
            val initialLoadGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdLoadUntil = initialLoadGate)
            val store = createMessageStore(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceTimeBy(1)

            assertEquals(emptyList<List<Message>>(), emissions)

            initialLoadGate.complete(Unit)
            advanceTimeBy(1)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS + 1)

            assertEquals(1, emissions.size)
            assertEquals("Hello", emissions.single().single().text)
        }

    @Test
    fun observeMessages_fullReloadOnReconnected() =
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
            eventStream.emit(OpencodeEvent(type = "stream.reconnected", sessionId = "ses-1"))
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
            eventStream.emit(OpencodeEvent(type = "stream.reconnected", sessionId = "ses-1"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals(1, emissions.size)
            assertEquals("Hello", emissions.single().single().text)
        }

    @Test
    fun observeMessages_messageUpdatedWithInfo_suppressesEmptyNewSlot() =
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

            val infoJson =
                buildJsonObject {
                    put("id", "msg-2")
                    put("role", "assistant")
                    put("sessionID", "ses-1")
                }
            val props =
                buildJsonObject {
                    put("info", infoJson)
                }
            eventStream.emit(OpencodeEvent(type = "message.updated", sessionId = "ses-1", rawProperties = props))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals(1, emissions.size)
            assertEquals(listOf("msg-1"), emissions.last().map { it.id })
        }

    @Test
    fun observeMessages_messagePartUpdatedWithText_appendsPart() =
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

            // First create a message slot.
            val infoJson =
                buildJsonObject {
                    put("id", "msg-2")
                    put("role", "user")
                    put("sessionID", "ses-1")
                }
            eventStream.emit(
                OpencodeEvent(
                    type = "message.updated",
                    sessionId = "ses-1",
                    rawProperties = buildJsonObject { put("info", infoJson) },
                ),
            )
            advanceTimeBy(50)

            // Then add a text part.
            val partJson =
                buildJsonObject {
                    put("id", "prt-1")
                    put("messageID", "msg-2")
                    put("sessionID", "ses-1")
                    put("type", "text")
                    put("text", "Hello from part")
                }
            eventStream.emit(
                OpencodeEvent(
                    type = "message.part.updated",
                    sessionId = "ses-1",
                    rawProperties = buildJsonObject { put("part", partJson) },
                ),
            )
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals(2, emissions.size)
            val last = emissions.last()
            assertEquals(listOf("msg-1", "msg-2"), last.map { it.id })
            val msg2 = last.first { it.id == "msg-2" }
            assertTrue(msg2.text.contains("Hello from part"))
        }

    @Test
    fun observeMessages_messagePartUpdatedWithTextDelta_appendsToExistingPart() =
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

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            advanceTimeBy(50)

            eventStream.emit(textDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Hel"))
            eventStream.emit(textDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "lo"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val msg2 = emissions.last().first { it.id == "msg-2" }
            assertEquals(listOf("msg-1", "msg-2"), emissions.last().map { it.id })
            assertEquals("Hello", msg2.text)
            val textPart = msg2.parts.single() as MessagePart.Text
            assertTrue(textPart.isStreaming)
        }

    @Test
    fun observeMessages_messagePartUpdated_preservesPartOrderAcrossReplacement() =
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

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            advanceTimeBy(50)

            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = "First"))
            eventStream.emit(toolPartEvent(partId = "prt-t1", messageId = "msg-2", tool = "bash", status = "pending"))
            eventStream.emit(textPartEvent(partId = "prt-2", messageId = "msg-2", text = "Second"))
            eventStream.emit(toolPartEvent(partId = "prt-t1", messageId = "msg-2", tool = "bash", status = "completed"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val msg2 = emissions.last().first { it.id == "msg-2" }
            assertEquals(listOf("msg-1", "msg-2"), emissions.last().map { it.id })
            assertEquals("First\n\u2713 bash\nSecond", msg2.text)
            assertEquals(3, msg2.parts.size)
        }

    @Test
    fun observeMessages_partUpdateForRestSeededMessageReloadsTimeline() =
        runTest(testDispatcher) {
            val repository =
                FakeMessageRepository(
                    messages =
                        listOf(
                            Message(
                                id = "msg-1",
                                role = MessageRole.Assistant,
                                text = "Old",
                                parts = listOf(MessagePart.Text("Old")),
                            ),
                        ),
                )
            val eventStream = FakeForegroundEventStream()
            val store = createMessageStore(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { emissions.add(it) }
                }
            advanceTimeBy(1)

            repository.messages =
                listOf(
                    Message(
                        id = "msg-1",
                        role = MessageRole.Assistant,
                        text = "New",
                        parts = listOf(MessagePart.Text("New")),
                    ),
                )
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-1", text = "New"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals("New", emissions.last().single().text)
            assertEquals(2, repository.listMessagesCallCount)
        }

    private fun createMessageStore(
        repository: FakeMessageRepository,
        eventStream: FakeForegroundEventStream,
    ): DefaultMessageLiveStore {
        val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        return DefaultMessageLiveStore(repository, eventStream, scope)
    }

    private fun messageUpdatedEvent(
        messageId: String,
        role: String,
    ): OpencodeEvent {
        val infoJson =
            buildJsonObject {
                put("id", messageId)
                put("role", role)
                put("sessionID", "ses-1")
            }
        return OpencodeEvent(
            type = "message.updated",
            sessionId = "ses-1",
            rawProperties = buildJsonObject { put("info", infoJson) },
        )
    }

    private fun textPartEvent(
        partId: String,
        messageId: String,
        text: String,
    ): OpencodeEvent {
        val partJson =
            buildJsonObject {
                put("id", partId)
                put("messageID", messageId)
                put("sessionID", "ses-1")
                put("type", "text")
                put("text", text)
            }
        return OpencodeEvent(
            type = "message.part.updated",
            sessionId = "ses-1",
            rawProperties = buildJsonObject { put("part", partJson) },
        )
    }

    private fun textDeltaEvent(
        partId: String,
        messageId: String,
        delta: String,
    ): OpencodeEvent {
        val partJson =
            buildJsonObject {
                put("id", partId)
                put("messageID", messageId)
                put("sessionID", "ses-1")
                put("type", "text")
            }
        return OpencodeEvent(
            type = "message.part.updated",
            sessionId = "ses-1",
            rawProperties =
                buildJsonObject {
                    put("part", partJson)
                    put("delta", delta)
                },
        )
    }

    private fun toolPartEvent(
        partId: String,
        messageId: String,
        tool: String,
        status: String,
    ): OpencodeEvent {
        val stateJson = buildJsonObject { put("status", status) }
        val partJson =
            buildJsonObject {
                put("id", partId)
                put("messageID", messageId)
                put("sessionID", "ses-1")
                put("type", "tool")
                put("tool", tool)
                put("state", stateJson)
            }
        return OpencodeEvent(
            type = "message.part.updated",
            sessionId = "ses-1",
            rawProperties = buildJsonObject { put("part", partJson) },
        )
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
    private val holdLoadUntil: CompletableDeferred<Unit>? = null,
) : MessageRepository {
    var listMessagesCallCount = 0

    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> {
        holdLoadUntil?.await()
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
