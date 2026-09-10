package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.FakeWorldSignals
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.WorldSignal
import dev.opencode.mobile.domain.message.InMemoryMessageStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageSync
import dev.opencode.mobile.domain.message.SessionMessageEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionReconciliationTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun lateSubscriber_seesOpenGapAndFetches() =
        runTest(testDispatcher) {
            val repository = RecordingMessageRepository()
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            eventStream.stop()
            eventStream.setConnectionState(EventStreamConnectionState.Connecting)
            repository.messages = listOf(Message(id = "msg-1", role = MessageRole.User, text = "After gap"))
            val sync = createSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val job =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            job.cancel()
            assertEquals("After gap", emissions.last().single().text)
            assertEquals(1, repository.listMessagesCallCount)
        }

    @Test
    fun worldAndReconnectPair_yieldsOneGet() =
        runTest(testDispatcher) {
            val repository = RecordingMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val world = FakeWorldSignals()
            val sync = createSync(repository, eventStream, world)
            val job = launch { sync.observeMessages("ses-1", "/repo").collect { } }
            advanceTimeBy(1)
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            advanceTimeBy(1)
            val afterInitial = repository.listMessagesCallCount
            world.emit(WorldSignal.BackgroundStop)
            world.emit(WorldSignal.Foreground)
            eventStream.emit(OpencodeEvent(type = "stream.reconnected"))
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(350)
            job.cancel()
            assertEquals(afterInitial + 1, repository.listMessagesCallCount)
        }

    @Test
    fun briefGrace_doesNotFetchOrShowSyncing() =
        runTest(testDispatcher) {
            val repository = RecordingMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val world = FakeWorldSignals()
            val sync = createSync(repository, eventStream, world)
            var catchingUp = false
            val job = launch { sync.observeMessages("ses-1", "/repo").collect { } }
            val chrome = launch { sync.observeCatchUpChrome("ses-1", "/repo").collect { catchingUp = it } }
            advanceTimeBy(1)
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            advanceTimeBy(1)
            val afterInitial = repository.listMessagesCallCount
            world.emit(WorldSignal.Background)
            advanceTimeBy(1)
            world.emit(WorldSignal.Foreground)
            advanceTimeBy(1)
            job.cancel()
            chrome.cancel()
            assertEquals(afterInitial, repository.listMessagesCallCount)
            assertFalse(catchingUp)
        }

    @Test
    fun profileGeneration_doesNotPublishIntoOldObservation() =
        runTest(testDispatcher) {
            val repository = RecordingMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val job =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            sync.clearCache()
            repository.messages = listOf(Message(id = "msg-old", role = MessageRole.User, text = "Old profile"))
            eventStream.openGap()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            eventStream.emit(OpencodeEvent(type = "stream.reconnected"))
            advanceTimeBy(50)
            job.cancel()
            assertEquals("Hello", emissions.last().single().text)
        }

    @Test
    fun failedReload_remainsBehindAndRetriesSameGap() =
        runTest(testDispatcher) {
            val repository = RecordingMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createSync(repository, eventStream)
            val job = launch { sync.observeMessages("ses-1", "/repo").collect { } }
            advanceTimeBy(1)
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            advanceTimeBy(1)
            val afterInitial = repository.listMessagesCallCount
            repository.shouldFail = true
            eventStream.openGap()
            eventStream.emit(OpencodeEvent(type = "stream.reconnected"))
            advanceTimeBy(50)
            assertTrue(repository.listMessagesCallCount > afterInitial)
            val afterFail = repository.listMessagesCallCount
            repository.shouldFail = false
            repository.messages = listOf(Message(id = "msg-1", role = MessageRole.User, text = "Recovered"))
            advanceTimeBy(50)
            job.cancel()
            assertTrue(repository.listMessagesCallCount > afterFail)
        }

    @Test
    fun sseDuringRest_winsOverStaleSnapshot() =
        runTest(testDispatcher) {
            val repository = RecordingMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createSync(repository, eventStream, pollIntervalMs = 40L)
            val emissions = mutableListOf<List<Message>>()
            val job =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            eventStream.emit(
                OpencodeEvent(
                    type = "message.updated",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put(
                                "info",
                                buildJsonObject {
                                    put("id", "msg-2")
                                    put("role", "assistant")
                                    put("sessionID", "ses-1")
                                },
                            )
                        },
                ),
            )
            eventStream.emit(
                OpencodeEvent(
                    type = "message.part.updated",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put(
                                "part",
                                buildJsonObject {
                                    put("id", "prt-1")
                                    put("messageID", "msg-2")
                                    put("sessionID", "ses-1")
                                    put("type", "text")
                                    put("text", "")
                                },
                            )
                        },
                ),
            )
            advanceTimeBy(1)
            val gate = CompletableDeferred<Unit>()
            repository.nextLoadGate = gate
            repository.messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = ""),
                )
            advanceTimeBy(41)
            eventStream.emit(
                OpencodeEvent(
                    type = "message.part.delta",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put("sessionID", "ses-1")
                            put("messageID", "msg-2")
                            put("partID", "prt-1")
                            put("field", "text")
                            put("delta", "Live SSE")
                        },
                ),
            )
            advanceTimeBy(1)
            gate.complete(Unit)
            advanceTimeBy(1)
            job.cancel()
            assertEquals("Live SSE", emissions.last().first { it.id == "msg-2" }.text)
        }

    private fun createSync(
        repository: RecordingMessageRepository,
        eventStream: FakeForegroundEventStream,
        world: FakeWorldSignals = FakeWorldSignals(),
        pollIntervalMs: Long = 60_000L,
    ): MessageSync =
        MessageSync(
            listMessages = { sessionId, directory -> repository.listMessages(sessionId, directory) },
            engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
            eventStream = eventStream,
            liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
            worldSignals = world,
            pollIntervalMs = pollIntervalMs,
            recoveryDelay = { delay(1) },
            nowMs = { testDispatcher.scheduler.currentTime },
        )
}

private class RecordingMessageRepository {
    var messages: List<Message> = listOf(Message(id = "msg-1", role = MessageRole.User, text = "Hello"))
    var listMessagesCallCount = 0
    var shouldFail = false
    var nextLoadGate: CompletableDeferred<Unit>? = null

    suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> {
        nextLoadGate?.also {
            nextLoadGate = null
            it.await()
        }
        listMessagesCallCount += 1
        if (shouldFail) error("load failed")
        return messages
    }
}
