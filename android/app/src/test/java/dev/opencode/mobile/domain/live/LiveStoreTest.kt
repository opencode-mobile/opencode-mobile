package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.FakeWorldSignals
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.event.StoreRefreshReason
import dev.opencode.mobile.domain.event.WorldSignal
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.DisplayMessagePipeline
import dev.opencode.mobile.domain.message.InMemoryMessageStore
import dev.opencode.mobile.domain.message.MESSAGE_EVENT_STAGING_CAPACITY
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessagePatch
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageSync
import dev.opencode.mobile.domain.message.MessageSyncHealth
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.message.STREAMING_PREPARE_DEBOUNCE_MS
import dev.opencode.mobile.domain.message.SessionMessageEngine
import dev.opencode.mobile.domain.message.ShellCommandOptions
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionInfo
import dev.opencode.mobile.domain.session.QuestionOption
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import dev.opencode.mobile.domain.session.SessionActivitySource
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.sessioncache.DefaultSessionCache
import dev.opencode.mobile.domain.sessioncache.InMemorySessionCacheStore
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.domain.sessioncache.SessionActivityCacheEntry
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sessioncache.SessionMessageCacheHandle
import dev.opencode.mobile.domain.sync.testSyncCoordinator
import dev.opencode.mobile.markdown.OpenCodeSyntaxHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class MessageSyncTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun observeMessages_emitsInitialMessages() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val sync = createMessageSync(repository, FakeForegroundEventStream())

            val messages = sync.firstMessages("ses-1", "/repo")

            assertEquals("Hello", messages.single().text)
        }

    @Test
    fun observeMessages_doesNotEmitEmptyBeforeInitialLoadCompletes() =
        runTest(testDispatcher) {
            val initialLoadGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdLoadUntil = initialLoadGate)
            val store = createMessageSync(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
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
    fun observeMessages_buffersEventsWhileInitialRestLoadIsInFlight() =
        runTest(testDispatcher) {
            val initialLoadGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdLoadUntil = initialLoadGate)
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Hello"))
            initialLoadGate.complete(Unit)
            advanceTimeBy(1)

            assertEquals("Hello", emissions.last().first { it.id == "msg-2" }.text)
            collectJob.cancel()
        }

    @Test
    fun observeMessages_subscribeWhileLiveUnpaidGap_doesNotStartSecondRest() =
        runTest(testDispatcher) {
            val initialLoadGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdLoadUntil = initialLoadGate)
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            eventStream.openGap()
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { }
                }
            advanceTimeBy(1)
            runCurrent()

            assertEquals(1, repository.listMessagesStarted.get())

            initialLoadGate.complete(Unit)
            advanceTimeBy(1)
            runCurrent()
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_overflowDuringInitialRestTriggersAuthoritativeRecovery() =
        runTest(testDispatcher) {
            val initialLoadGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(holdLoadUntil = initialLoadGate)
            repository.messages =
                listOf(
                    Message(id = "msg-rest", role = MessageRole.Assistant, text = "from-rest"),
                )
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream, eventStagingCapacity = 2)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            // Fill staging beyond capacity while REST is held.
            repeat(4) { index ->
                eventStream.emit(messageUpdatedEvent("msg-burst-$index", "assistant"))
            }
            // Authoritative recovery reload sees the recovered snapshot.
            repository.messages =
                listOf(
                    Message(id = "msg-recovered", role = MessageRole.Assistant, text = "recovered"),
                )
            initialLoadGate.complete(Unit)
            advanceTimeBy(10)
            runCurrent()

            assertTrue(emissions.isNotEmpty())
            assertEquals("recovered", emissions.last().single().text)
            assertTrue(emissions.none { frame -> frame.any { it.id == "msg-rest" && it.text == "from-rest" } })
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_overflowRecoveryFailureKeepsRecoveringAndLastFrame() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            assertEquals("Hello", emissions.last().single().text)

            repository.shouldFailLoad = true
            eventStream.openGap()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            eventStream.emit(OpencodeEvent(type = "stream.reconnected", sessionId = "ses-1"))
            advanceTimeBy(50)

            assertEquals("Hello", emissions.last().single().text)
            assertTrue(sync.syncHealth("ses-1", "/repo") != MessageSyncHealth.Fresh)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_missingSessionStopsOverflowRecovery() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { }
                }
            advanceTimeBy(1)
            val callsAfterInitial = repository.listMessagesCallCount

            repository.shouldFailMissingSession = true
            eventStream.openGap()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            eventStream.emit(OpencodeEvent(type = "stream.reconnected", sessionId = "ses-1"))
            advanceTimeBy(20_000)

            assertTrue(repository.listMessagesCallCount <= callsAfterInitial + 2)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_sessionKeysIsolateProfiles() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val syncA = createMessageSync(repository, eventStream, connectionProfileId = "profile-a")
            val syncB = createMessageSync(repository, eventStream, connectionProfileId = "profile-b")

            val messagesA = syncA.firstMessages("ses-1", "/repo")
            repository.messages = listOf(Message(id = "msg-b", role = MessageRole.Assistant, text = "B"))
            val messagesB = syncB.firstMessages("ses-1", "/repo")

            assertEquals("Hello", messagesA.single().text)
            assertEquals("B", messagesB.single().text)
        }

    @Test
    fun observeMessages_routerIgnoresNonMessageEvents() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            val before = emissions.size
            eventStream.emit(OpencodeEvent(type = "permission.asked", sessionId = "ses-1"))
            eventStream.emit(OpencodeEvent(type = "session.created", sessionId = "ses-1"))
            advanceTimeBy(50)
            assertEquals(before, emissions.size)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_byteCapacityOverflowEntersRecovering() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
            val sync =
                MessageSync(
                    listMessages = { sessionId, directory -> repository.listMessages(sessionId, directory) },
                    engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                    eventStream = eventStream,
                    liveStoreScope = scope,
                    syncCoordinator =
                        testSyncCoordinator(
                            eventStream = eventStream,
                            worldSignals = SilentWorldSignals,
                            scope = scope.coroutineScope,
                            nowMs = { testDispatcher.scheduler.currentTime },
                            wait = { delay(1) },
                        ),
                    eventStagingCapacity = 64,
                    eventStagingByteCapacity = 200,
                )
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            assertEquals("Hello", emissions.last().single().text)

            repository.shouldFailLoad = true
            repeat(8) {
                eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-1", delta = "x".repeat(80)))
            }
            advanceTimeBy(50)
            assertTrue(sync.syncHealth("ses-1", "/repo") != MessageSyncHealth.Fresh)
            assertEquals("Hello", emissions.last().single().text)
            repository.shouldFailLoad = false
            advanceTimeBy(50)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_silentStreamingStall_appliesAheadRestSnapshot() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 40L)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Partial"))
            advanceTimeBy(1)
            assertEquals("Partial", emissions.last().first { it.id == "msg-2" }.text)

            repository.messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "Finished while silent",
                        parts = listOf(MessagePart.Text(text = "Finished while silent", isStreaming = false)),
                    ),
                )
            eventStream.openGap()
            runCurrent()
            advanceTimeBy(5)
            runCurrent()
            assertEquals(
                "Finished while silent",
                emissions.last().first { it.id == "msg-2" }.text,
            )
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_silentStreamingStall_keepsLocalWhenRestIsBehind() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 40L)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Longer local stream"))
            advanceTimeBy(1)
            assertEquals("Longer local stream", emissions.last().first { it.id == "msg-2" }.text)

            repository.messages =
                listOf(
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "Short",
                        parts = listOf(MessagePart.Text(text = "Short", isStreaming = false)),
                    ),
                )
            advanceTimeBy(80)
            assertEquals("Longer local stream", emissions.last().first { it.id == "msg-2" }.text)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_transportDropEntersStaleWhileRetainingFrame() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            assertEquals("Hello", emissions.last().single().text)

            eventStream.start()
            advanceTimeBy(1)
            eventStream.setConnectionState(EventStreamConnectionState.Disconnected)
            advanceTimeBy(1)

            val health = sync.syncHealth("ses-1", "/repo")
            assertTrue(health is MessageSyncHealth.Stale)
            assertEquals("Hello", emissions.last().single().text)
            val chrome = mutableListOf<Boolean>()
            val chromeJob =
                launch {
                    sync.observeCatchUpChrome("ses-1", "/repo").collect { chrome.add(it) }
                }
            advanceTimeBy(1)
            assertEquals(true, chrome.lastOrNull())

            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-1", text = "Hello"))
            advanceTimeBy(1)
            runCurrent()
            assertEquals(true, chrome.lastOrNull())
            assertTrue(sync.syncHealth("ses-1", "/repo") is MessageSyncHealth.Stale)
            chromeJob.cancel()
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun refreshMessages_whileHealthy_doesNotShowCatchUpChrome() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { }
                }
            advanceTimeBy(1)
            runCurrent()
            assertEquals(MessageSyncHealth.Fresh, sync.syncHealth("ses-1", "/repo"))

            val chrome = mutableListOf<Boolean>()
            val chromeJob =
                launch {
                    sync.observeCatchUpChrome("ses-1", "/repo").collect { chrome.add(it) }
                }
            advanceTimeBy(1)
            runCurrent()
            assertEquals(false, chrome.lastOrNull())

            val refreshHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = refreshHold
            val refreshJob = launch { sync.refreshMessages("ses-1", "/repo") }
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, repository.listHoldsEntered.get())
            assertFalse(chrome.contains(true))
            assertEquals(MessageSyncHealth.Fresh, sync.syncHealth("ses-1", "/repo"))

            refreshHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()
            refreshJob.join()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            chromeJob.cancel()
            collectJob.cancel()
            assertFalse(chrome.contains(true))
            assertEquals(MessageSyncHealth.Fresh, sync.syncHealth("ses-1", "/repo"))
        }

    @Test
    fun refreshMessages_overlappingRefreshes_doNotShowCatchUpChrome() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { }
                }
            advanceTimeBy(1)
            runCurrent()
            assertEquals(MessageSyncHealth.Fresh, sync.syncHealth("ses-1", "/repo"))

            val chrome = mutableListOf<Boolean>()
            val chromeJob =
                launch {
                    sync.observeCatchUpChrome("ses-1", "/repo").collect { chrome.add(it) }
                }
            advanceTimeBy(1)
            runCurrent()
            assertEquals(false, chrome.lastOrNull())

            val refreshHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = refreshHold
            val firstRefresh = launch { sync.refreshMessages("ses-1", "/repo") }
            val secondRefresh = launch { sync.refreshMessages("ses-1", "/repo") }
            advanceTimeBy(1)
            runCurrent()
            assertFalse(chrome.contains(true))

            refreshHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()
            firstRefresh.join()
            secondRefresh.join()
            runCurrent()
            assertFalse(chrome.contains(true))
            assertEquals(MessageSyncHealth.Fresh, sync.syncHealth("ses-1", "/repo"))
            chromeJob.cancel()
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_hiddenReasoningSnapshotUpdates_doNotRestOrShowCatchUp() =
        runTest(testDispatcher) {
            val seed = hiddenReasoningTranscript()
            val repository = FakeMessageRepository(messages = seed)
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val initialHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = initialHold
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val emissions = mutableListOf<List<Message>>()
            val chrome = mutableListOf<Boolean>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            val chromeJob =
                launch {
                    sync.observeCatchUpChrome("ses-1", "/repo").collect { chrome.add(it) }
                }
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, repository.listHoldsEntered.get())
            initialHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(seed, emissions.last())
            val afterInitial = repository.listMessagesCallCount
            assertEquals(1, afterInitial)
            assertEquals(MessageSyncHealth.Fresh, sync.syncHealth("ses-1", "/repo"))
            assertFalse(chrome.last())

            for (snapshotWake in 1..3) {
                eventStream.republishSameGeneration()
                runCurrent()
            }
            eventStream.emit(reasoningPartEvent(partId = "prt-r", messageId = "msg-2", text = ""))
            eventStream.emit(
                partDeltaEvent(
                    partId = "prt-r",
                    messageId = "msg-2",
                    delta = "Thinking",
                    field = "reasoning",
                ),
            )
            eventStream.emit(textPartEvent(partId = "prt-t", messageId = "msg-2", text = ""))
            eventStream.emit(
                partDeltaEvent(partId = "prt-t", messageId = "msg-2", delta = "Answer"),
            )
            advanceTimeBy(1)
            runCurrent()

            assertFalse(chrome.last())
            assertEquals(MessageSyncHealth.Fresh, sync.syncHealth("ses-1", "/repo"))
            val assistant = emissions.last().first { it.id == "msg-2" }
            assertEquals("Thinking", (assistant.parts[0] as MessagePart.Reasoning).text)
            assertEquals("Answer", (assistant.parts[1] as MessagePart.Text).text)
            collectJob.cancel()
            chromeJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_hiddenReasoningTransportGap_reconcilesOnce() =
        runTest(testDispatcher) {
            val seed = hiddenReasoningTranscript()
            val repository = FakeMessageRepository(messages = seed)
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val initialHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = initialHold
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val chrome = mutableListOf<Boolean>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { }
                }
            val chromeJob =
                launch {
                    sync.observeCatchUpChrome("ses-1", "/repo").collect { chrome.add(it) }
                }
            advanceTimeBy(1)
            runCurrent()
            initialHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()
            val afterInitial = repository.listMessagesCallCount
            assertEquals(1, afterInitial)

            val recoveryHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = recoveryHold
            repository.messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "Done",
                        parts = listOf(MessagePart.Text(text = "Done")),
                    ),
                )
            eventStream.openGap()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, repository.listHoldsEntered.get())
            assertEquals(afterInitial, repository.listMessagesCallCount)
            recoveryHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()

            assertEquals(afterInitial + 1, repository.listMessagesCallCount)
            assertEquals(MessageSyncHealth.Fresh, sync.syncHealth("ses-1", "/repo"))
            assertFalse(chrome.last())
            collectJob.cancel()
            chromeJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_idleDuringInitialSubscribe_paysThenOneIdleRest() =
        runTest(testDispatcher) {
            val seed = hiddenReasoningTranscript()
            val completed = completedAssistantTranscript()
            val repository = FakeMessageRepository(messages = seed)
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val initialHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = initialHold
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val chrome = mutableListOf<Boolean>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { }
                }
            val chromeJob =
                launch {
                    sync.observeCatchUpChrome("ses-1", "/repo").collect { chrome.add(it) }
                }
            advanceTimeBy(1)
            runCurrent()
            assertTrue(eventStream.hasEventSubscriber)
            assertEquals(1, repository.listHoldsEntered.get())

            repeat(5) {
                eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            }
            val idleHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = idleHold
            initialHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()

            repository.messages = completed
            idleHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()

            assertEquals(MessageSyncHealth.Fresh, sync.syncHealth("ses-1", "/repo"))
            assertFalse(chrome.last())
            collectJob.cancel()
            chromeJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_idleDuringInitialSubscribe_visibleThoughtStillRefetchesCompletedTail() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository(messages = visibleThoughtTranscript())
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val initialHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = initialHold
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            runCurrent()
            assertTrue(eventStream.hasEventSubscriber)
            assertEquals(1, repository.listHoldsEntered.get())

            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            val idleHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = idleHold
            initialHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, repository.listHoldsEntered.get())
            repository.messages = completedTurnWithExtraAssistant()
            idleHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()

            assertTrue(emissions.last().any { it.id == "msg-3" })
            assertTrue(repository.listMessagesCallCount >= 2)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_repeatedIncompleteIdle_reconcilesOnce() =
        runTest(testDispatcher) {
            val seed = hiddenReasoningTranscript()
            val repository = FakeMessageRepository(messages = seed)
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val initialHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = initialHold
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { }
                }
            advanceTimeBy(1)
            runCurrent()
            initialHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()
            val afterInitial = repository.listMessagesCallCount
            assertEquals(1, afterInitial)

            val idleHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = idleHold
            repository.messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "Done",
                        parts = listOf(MessagePart.Text(text = "Done")),
                    ),
                )
            repeat(5) {
                eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            }
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, repository.listHoldsEntered.get())
            assertEquals(afterInitial, repository.listMessagesCallCount)
            idleHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()

            assertEquals(MessageSyncHealth.Fresh, sync.syncHealth("ses-1", "/repo"))
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeCatchUpChrome_unscoped_clearsRecoveredSessionAndStaysBounded() =
        runTest(testDispatcher) {
            val seed = hiddenReasoningTranscript()
            val repository = FakeMessageRepository(messages = seed)
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val initialHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = initialHold
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)
            val globalChrome = mutableListOf<Boolean>()
            val chromeJob =
                launch {
                    sync.observeCatchUpChrome().collect { globalChrome.add(it) }
                }
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { }
                }
            advanceTimeBy(1)
            runCurrent()
            initialHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(globalChrome.last())

            val recoveryHold = CompletableDeferred<Unit>()
            repository.nextLoadGate = recoveryHold
            eventStream.openGap()
            advanceTimeBy(1)
            runCurrent()
            assertTrue(globalChrome.contains(true))
            recoveryHold.complete(Unit)
            advanceTimeBy(1)
            runCurrent()
            assertFalse(globalChrome.last())

            for (snapshotWake in 1..3) {
                eventStream.republishSameGeneration()
                runCurrent()
            }
            assertFalse(globalChrome.last())
            assertEquals(2, repository.listMessagesCallCount)

            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
            assertFalse(globalChrome.last())
            chromeJob.cancel()
        }

    @Test
    fun observeMessages_reconnectUnchangedIdentityDoesNotRepaint() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(OpencodeEvent(type = "stream.reconnected", sessionId = "ses-1"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            // The reconnect REST returns a byte-identical tail: no repaint.
            assertEquals(1, emissions.size)
            assertEquals("Hello", emissions.single().single().text)
        }

    @Test
    fun observeMessages_doesNotPollWhileEventStreamConnected() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            repository.messages = listOf(Message(id = "msg-2", role = MessageRole.Assistant, text = "Polled"))
            advanceTimeBy(
                10_300L,
            )
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals(1, emissions.size)
            assertEquals("Hello", emissions.single().single().text)
        }

    @Test
    fun observeMessages_ignoresEventsForOtherSessions() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
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
    fun observeMessages_pollsTimelineWhenIdentityChanges() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            repository.messages = listOf(Message(id = "msg-2", role = MessageRole.Assistant, text = "Polled"))
            eventStream.openGap()
            runCurrent()
            advanceTimeBy(5)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals(2, emissions.size)
            assertEquals("Polled", emissions.last().single().text)
        }

    @Test
    fun observeMessages_doesNotEmitDuplicateOnUnchangedPoll() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val store = createMessageSync(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            advanceTimeBy(
                10_300L,
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
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            repository.shouldFailLoad = true
            eventStream.openGap()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
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
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
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
    fun observeMessages_v2PromptedPublishesUserText() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            val adapter = EventDomainAdapter(Json { ignoreUnknownKeys = true })
            adapter
                .parseEvents(
                    """{"type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-2","prompt":{"text":"Hello from CLI"},"delivery":"steer"}}""",
                ).forEach(eventStream::emit)
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val last = emissions.last()
            assertEquals(listOf("msg-1", "msg-2"), last.map { it.id })
            assertEquals("Hello from CLI", last.first { it.id == "msg-2" }.text)
        }

    @Test
    fun observeMessages_v2InboxEnqueuedPublishesUserText() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            val adapter = EventDomainAdapter(Json { ignoreUnknownKeys = true })
            adapter
                .parseEvents(
                    """{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"Hello from inbox","files":[]},"delivery":"steer"}}}""",
                ).forEach(eventStream::emit)
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val last = emissions.last()
            assertEquals(listOf("msg-1", "msg-2"), last.map { it.id })
            assertEquals("Hello from inbox", last.first { it.id == "msg-2" }.text)
        }

    @Test
    fun observeMessages_v2PromptedGlobalBusPublishesUserText() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            val adapter = EventDomainAdapter(Json { ignoreUnknownKeys = true })
            adapter
                .parseEvents(
                    """{"directory":"/repo","payload":{"type":"session.next.prompted","properties":{"sessionID":"ses-1","messageID":"msg-2","prompt":{"text":"Hello from CLI"},"delivery":"steer"}}}""",
                ).forEach(eventStream::emit)
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val last = emissions.last()
            assertEquals(listOf("msg-1", "msg-2"), last.map { it.id })
            assertEquals("Hello from CLI", last.first { it.id == "msg-2" }.text)
        }

    @Test
    fun observeMessages_messagePartUpdatedWithText_appendsPart() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
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
    fun observeMessages_pollDoesNotRegressStreamingText() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            advanceTimeBy(50)
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Hello"))
            advanceTimeBy(50)
            assertEquals("Hello", emissions.last().first { it.id == "msg-2" }.text)

            // Mid-stream REST snapshot is behind the live deltas (empty assistant text).
            repository.messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "",
                        parts = listOf(MessagePart.Text(text = "", isStreaming = false)),
                    ),
                )
            advanceTimeBy(10_000L + 1)

            assertEquals(
                "Hello",
                emissions.last().first { it.id == "msg-2" }.text,
            )

            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "!"))
            advanceTimeBy(50)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals("Hello!", emissions.last().first { it.id == "msg-2" }.text)
        }

    @Test
    fun observeMessages_discardsPollResponseWhenStreamingStartsInFlight() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            advanceTimeBy(1)

            repository.messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
                    Message(id = "msg-2", role = MessageRole.Assistant, text = ""),
                )
            val pollGate = CompletableDeferred<Unit>()
            repository.nextLoadGate = pollGate
            advanceTimeBy(10_000L)

            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Live"))
            advanceTimeBy(1)
            pollGate.complete(Unit)
            advanceTimeBy(1)

            assertEquals("Live", emissions.last().first { it.id == "msg-2" }.text)
            collectJob.cancel()
        }

    @Test
    fun observeMessages_messagePartDelta_keepsReasoningSeparateFromText() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            advanceTimeBy(50)
            eventStream.emit(reasoningPartEvent(partId = "prt-reason", messageId = "msg-2", text = ""))
            eventStream.emit(
                partDeltaEvent(
                    partId = "prt-reason",
                    messageId = "msg-2",
                    delta = "The user said hello.",
                    field = "reasoning",
                ),
            )
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Hello!"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val msg2 = emissions.last().first { it.id == "msg-2" }
            assertEquals("Hello!", msg2.text)
            assertEquals(2, msg2.parts.size)
            assertTrue(msg2.parts[0] is MessagePart.Reasoning)
            assertEquals("The user said hello.", (msg2.parts[0] as MessagePart.Reasoning).text)
            assertTrue(msg2.parts[1] is MessagePart.Text)
        }

    @Test
    fun observeMessages_sessionIdleFinalizesStreamingWithoutReload() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            advanceTimeBy(50)
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Hello"))
            advanceTimeBy(50)
            repository.messages =
                listOf(
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "Hello",
                        parts = listOf(MessagePart.Text(text = "Hello", isStreaming = false)),
                    ),
                )
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            advanceTimeBy(50)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val final = emissions.last()
            assertEquals("Hello", final.first { it.id == "msg-1" }.text)
            val assistant = final.first { it.id == "msg-2" }
            assertEquals("Hello", assistant.text)
            assertFalse((assistant.parts.single() as MessagePart.Text).isStreaming)
            assertEquals(1, repository.listMessagesCallCount)
        }

    @Test
    fun observeMessages_sessionIdleReloadsWhenLastAssistantHasNoVisibleContent() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            repository.messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "",
                        parts = listOf(MessagePart.Reasoning(text = "")),
                    ),
                )
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val sync = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            val afterInitial = repository.listMessagesCallCount
            repository.messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "Done",
                        parts = listOf(MessagePart.Text(text = "Done")),
                    ),
                )
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            advanceTimeBy(50)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertTrue(repository.listMessagesCallCount > afterInitial)
            assertEquals("Done", emissions.last().first { it.id == "msg-2" }.text)
        }

    @Test
    fun displayPipelinePublishesStreamingAndFinalStatesFromRealDeltaEvents() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream)
            val preparer =
                MessageContentPreparer(OpenCodeTextMateHighlightingEngine(OpenCodeSyntaxHighlighter.default)).apply {
                    computationDispatcher = testDispatcher
                }
            val pipeline = DisplayMessagePipeline(sync, preparer)
            val emissions = mutableListOf<dev.opencode.mobile.domain.message.DisplayMessage>()
            val collectJob =
                launch {
                    pipeline.observeDisplayMessages("ses-1", "/repo").collect { update ->
                        if (update is DisplayMessageListUpdate.Messages) {
                            update.messages.firstOrNull { it.id == "msg-2" }?.let(emissions::add)
                        }
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Hello"))
            advanceTimeBy(STREAMING_PREPARE_DEBOUNCE_MS + 1)
            assertTrue(emissions.last().isStreaming)
            assertEquals("Hello", emissions.last().text)

            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = "Hello"))
            advanceTimeBy(STREAMING_PREPARE_DEBOUNCE_MS + 1)
            assertFalse(emissions.last().isStreaming)
            collectJob.cancel()
        }

    @Test
    fun observeMessages_messagePartDelta_appendsStreamingText() =
        runTest(testDispatcher) {
            // OPE-82: server streams via dedicated message.part.delta, not delta on part.updated.
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            advanceTimeBy(50)
            // text-start: empty text part shell
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            advanceTimeBy(50)
            // text-delta chunks
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Hel"))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "lo"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val msg2 = emissions.last().first { it.id == "msg-2" }
            assertEquals("Hello", msg2.text)
            val textPart = msg2.parts.single() as MessagePart.Text
            assertTrue(textPart.isStreaming)
        }

    @Test
    fun observeMessages_messagePartDelta_ignoresNonTextField() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            advanceTimeBy(50)
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = "Hi"))
            advanceTimeBy(50)
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "!", field = "metadata"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val msg2 = emissions.last().first { it.id == "msg-2" }
            assertEquals("Hi", msg2.text)
            val textPart = msg2.parts.single() as MessagePart.Text
            assertFalse(textPart.isStreaming)
        }

    @Test
    fun observeMessages_messagePartDelta_ignoresMissingField() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            advanceTimeBy(50)
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = "Hi"))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "!", field = null))
            advanceTimeBy(50)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val textPart =
                emissions
                    .last()
                    .first { it.id == "msg-2" }
                    .parts
                    .single() as MessagePart.Text
            assertEquals("Hi", textPart.text)
            assertFalse(textPart.isStreaming)
        }

    @Test
    fun replaceAllClearsStreamingAndMakesRestAuthoritative() {
        val engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter()))
        engine.apply(
            "ses-1",
            MessagePatch.InfoUpdated(
                buildJsonObject {
                    put("id", "msg-2")
                    put("role", "assistant")
                    put("sessionID", "ses-1")
                },
            ),
        )
        engine.apply(
            "ses-1",
            MessagePatch.PartUpdated(
                buildJsonObject {
                    put("id", "prt-1")
                    put("messageID", "msg-2")
                    put("sessionID", "ses-1")
                    put("type", "text")
                    put("text", "")
                },
                delta = null,
            ),
        )
        engine.apply("ses-1", MessagePatch.PartDelta(messageId = "msg-2", partId = "prt-1", delta = "Hel"))
        engine.replaceAll(
            "ses-1",
            listOf(
                Message(
                    id = "msg-2",
                    role = MessageRole.Assistant,
                    text = "Final",
                    parts = listOf(MessagePart.Text(text = "Final", isStreaming = false)),
                ),
            ),
        )
        engine.apply("ses-1", MessagePatch.PartDelta(messageId = "msg-2", partId = "prt-1", delta = "lo"))

        val msg2 = engine.messages("ses-1").single { it.id == "msg-2" }
        assertEquals("Final", msg2.text)
        val textPart = msg2.parts.single() as MessagePart.Text
        assertFalse(textPart.isStreaming)
    }

    @Test
    fun observeMessages_messagePartDelta_finalPartUpdatedClearsStreaming() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            advanceTimeBy(50)
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Hello"))
            advanceTimeBy(50)
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = "Hello"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val msg2 = emissions.last().first { it.id == "msg-2" }
            assertEquals("Hello", msg2.text)
            val textPart = msg2.parts.single() as MessagePart.Text
            assertFalse(textPart.isStreaming)
        }

    @Test
    fun observeMessages_messagePartUpdated_preservesPartOrderAcrossReplacement() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
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
            // Tool labels stay on structured parts; flattened text is text-only.
            assertEquals("First\nSecond", msg2.text)
            assertEquals(3, msg2.parts.size)
            val first = msg2.parts[0] as MessagePart.Text
            val tool = msg2.parts[1] as MessagePart.Tool
            val second = msg2.parts[2] as MessagePart.Text
            assertEquals("First", first.text)
            assertEquals("bash", tool.toolName)
            assertEquals(ToolStatus.Completed, tool.status)
            assertEquals("Second", second.text)
        }

    @Test
    fun observeMessages_placeholderToolNameDoesNotOverwriteQuestion() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            eventStream.emit(toolPartEvent(partId = "prt-q", messageId = "msg-2", tool = "question", status = "pending"))
            eventStream.emit(toolPartEvent(partId = "prt-q", messageId = "msg-2", tool = "tool", status = "completed"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val tool =
                emissions
                    .last()
                    .first { it.id == "msg-2" }
                    .parts
                    .single() as MessagePart.Tool
            assertEquals("question", tool.toolName)
            assertEquals(ToolStatus.Completed, tool.status)
        }

    @Test
    fun observeMessages_namelessToolInfersWebsearchFromMergedQuery() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            eventStream.emit(toolPartEvent(partId = "prt-s", messageId = "msg-2", tool = "", status = "pending"))
            eventStream.emit(
                toolPartEvent(
                    partId = "prt-s",
                    messageId = "msg-2",
                    tool = "",
                    status = "completed",
                    input = buildJsonObject { put("query", "opencode mobile") },
                ),
            )
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val tool =
                emissions
                    .last()
                    .first { it.id == "msg-2" }
                    .parts
                    .single() as MessagePart.Tool
            assertEquals("websearch", tool.toolName)
            assertEquals(ToolStatus.Completed, tool.status)
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
                                text = "Old\nSibling",
                                parts = listOf(MessagePart.Text("Old"), MessagePart.Text("Sibling")),
                            ),
                        ),
                )
            val eventStream = FakeForegroundEventStream()
            val store = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    store.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            val callsAfterInitial = repository.listMessagesCallCount

            repository.messages =
                listOf(
                    Message(
                        id = "msg-1",
                        role = MessageRole.Assistant,
                        text = "New\nSibling",
                        parts = listOf(MessagePart.Text("New"), MessagePart.Text("Sibling")),
                    ),
                )
            // Info commonly arrives before the part update. It must not make a
            // REST-seeded row eligible for incomplete incremental replacement.
            eventStream.emit(messageUpdatedEvent("msg-1", "assistant"))
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-1", text = "New"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            assertEquals("New\nSibling", emissions.last().single().text)
            assertEquals(
                2,
                emissions
                    .last()
                    .single()
                    .parts.size,
            )
            assertEquals(callsAfterInitial + 1, repository.listMessagesCallCount)
        }

    @Test
    fun observeMessages_restSeededPartIdentityPreservesSiblingsAndStreamsFollowingDelta() =
        runTest(testDispatcher) {
            val repository =
                FakeMessageRepository(
                    messages =
                        listOf(
                            Message(
                                id = "msg-1",
                                role = MessageRole.Assistant,
                                text = "Old\nSibling",
                                parts =
                                    listOf(
                                        MessagePart.Text(id = "prt-1", text = "Old"),
                                        MessagePart.Text(id = "prt-2", text = "Sibling"),
                                    ),
                            ),
                        ),
                )
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream)
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            val callsAfterInitial = repository.listMessagesCallCount

            eventStream.emit(messageUpdatedEvent("msg-1", "assistant"))
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-1", text = "New"))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-1", delta = " live"))
            advanceTimeBy(1)

            val latest = emissions.last().single()
            assertEquals("New live\nSibling", latest.text)
            assertEquals(2, latest.parts.size)
            assertEquals(callsAfterInitial, repository.listMessagesCallCount)
            collectJob.cancel()
        }

    private fun createMessageSync(
        repository: FakeMessageRepository,
        eventStream: FakeForegroundEventStream,
        eventStagingCapacity: Int = MESSAGE_EVENT_STAGING_CAPACITY,
        connectionProfileId: String = "local",
        @Suppress("UNUSED_PARAMETER") pollIntervalMs: Long = 10_000L,
        worldSignals: WorldSignals = SilentWorldSignals,
    ): MessageSync {
        if (eventStream.connectionState.value == EventStreamConnectionState.Idle) {
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
        }
        val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        return MessageSync(
            listMessages = { sessionId, directory -> repository.listMessages(sessionId, directory) },
            engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
            eventStream = eventStream,
            liveStoreScope = scope,
            syncCoordinator =
                testSyncCoordinator(
                    eventStream = eventStream,
                    worldSignals = worldSignals,
                    scope = scope.coroutineScope,
                    nowMs = { testDispatcher.scheduler.currentTime },
                    wait = { delay(1) },
                ),
            eventStagingCapacity = eventStagingCapacity,
            connectionProfileIdProvider = { connectionProfileId },
            nowMs = { testDispatcher.scheduler.currentTime },
        )
    }

    private suspend fun MessageSync.firstMessages(
        sessionId: String,
        directory: String,
    ): List<Message> {
        val update = observeMessages(sessionId, directory).first()
        return when (update) {
            is MessageListUpdate.Messages -> update.messages
            is MessageListUpdate.LoadFailed -> throw update.error
            is MessageListUpdate.CacheSyncConfirmed -> emptyList()
        }
    }

    private fun hiddenReasoningTranscript(): List<Message> =
        listOf(
            Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
            Message(
                id = "msg-2",
                role = MessageRole.Assistant,
                text = "",
                parts = listOf(MessagePart.Reasoning(id = "prt-r", text = "", isStreaming = false)),
            ),
        )

    private fun completedAssistantTranscript(): List<Message> =
        listOf(
            Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
            Message(
                id = "msg-2",
                role = MessageRole.Assistant,
                text = "Done",
                parts = listOf(MessagePart.Text(text = "Done")),
            ),
        )

    private fun visibleThoughtTranscript(): List<Message> =
        listOf(
            Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
            Message(
                id = "msg-2",
                role = MessageRole.Assistant,
                text = "",
                parts = listOf(MessagePart.Reasoning(id = "prt-r", text = "Considering the request.", isStreaming = false)),
            ),
        )

    private fun completedTurnWithExtraAssistant(): List<Message> =
        visibleThoughtTranscript() +
            Message(
                id = "msg-3",
                role = MessageRole.Assistant,
                text = "Done",
                parts = listOf(MessagePart.Text(text = "Done")),
            )

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

    private fun reasoningPartEvent(
        partId: String,
        messageId: String,
        text: String,
    ): OpencodeEvent {
        val partJson =
            buildJsonObject {
                put("id", partId)
                put("messageID", messageId)
                put("sessionID", "ses-1")
                put("type", "reasoning")
                put("text", text)
            }
        return OpencodeEvent(
            type = "message.part.updated",
            sessionId = "ses-1",
            rawProperties = buildJsonObject { put("part", partJson) },
        )
    }

    private fun partDeltaEvent(
        partId: String,
        messageId: String,
        delta: String,
        field: String? = "text",
    ): OpencodeEvent =
        OpencodeEvent(
            type = "message.part.delta",
            sessionId = "ses-1",
            rawProperties =
                buildJsonObject {
                    put("sessionID", "ses-1")
                    put("messageID", messageId)
                    put("partID", partId)
                    field?.let { put("field", it) }
                    put("delta", delta)
                },
        )

    private fun toolPartEvent(
        partId: String,
        messageId: String,
        tool: String,
        status: String,
        input: JsonObject? = null,
    ): OpencodeEvent {
        val stateJson =
            buildJsonObject {
                put("status", status)
                input?.let { put("input", it) }
            }
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
    fun observeActivity_emitsRunningFromLoader() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val store = createStatusStore(repository, FakeForegroundEventStream())
            val snapshot = store.observeActivity("/repo").first()
            assertTrue(snapshot.seeded)
            assertEquals(SessionActivity.Running, snapshot.entries["ses-1"]?.activity)
            assertEquals(SessionActivitySource.Server, snapshot.entries["ses-1"]?.source)
        }

    @Test
    fun observeActivity_isUnseededUntilLoaderCompletes() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            repository.statusDelayMs = 10_000
            val store = createStatusStore(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            runCurrent()
            assertTrue(emissions.none { it.seeded })
            advanceTimeBy(10_000)
            advanceUntilIdle()
            collectJob.cancel()
            assertTrue(emissions.last().seeded)
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)
        }

    @Test
    fun observeActivity_keepsPriorWhenStatusLoadFails() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)
            repository.shouldFailStatuses = true
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(350)
            runCurrent()
            collectJob.cancel()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)
        }

    @Test
    fun observeActivity_appliesStatusEventLocallyWithoutWaitingForGet() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            val fetchesAfterBootstrap = repository.statusFetchCount
            eventStream.emit(busyStatusEvent("ses-1"))
            advanceUntilIdle()
            collectJob.cancel()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)
            assertEquals(fetchesAfterBootstrap, repository.statusFetchCount)
        }

    @Test
    fun stepShellCompactionEnded_doesNotTriggerStatusRest() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val collectJob = launch { store.observeActivity("/repo").collect { } }
            advanceUntilIdle()
            val fetchesAfterBootstrap = repository.statusFetchCount
            eventStream.emit(OpencodeEvent(type = "session.step.ended", sessionId = "ses-1"))
            eventStream.emit(OpencodeEvent(type = "session.shell.ended", sessionId = "ses-1"))
            eventStream.emit(OpencodeEvent(type = "session.compaction.ended", sessionId = "ses-1"))
            eventStream.emit(OpencodeEvent(type = "session.active.snapshot", sessionId = "ses-1"))
            advanceTimeBy(1_000)
            advanceUntilIdle()
            collectJob.cancel()
            assertEquals(fetchesAfterBootstrap, repository.statusFetchCount)
        }

    @Test
    fun observeActivity_removesSessionOnIdleEvent() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            advanceUntilIdle()
            collectJob.cancel()
            assertTrue(emissions.last().entries.isEmpty())
            assertTrue(emissions.last().seeded)
        }

    @Test
    fun observeActivity_refreshesOnServerConnectedAfterBackgroundResume() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            repository.statuses = emptyList()
            eventStream.openGap()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(350)
            advanceUntilIdle()
            collectJob.cancel()
            assertTrue(emissions.last().entries.isEmpty())
        }

    @Test
    fun setOptimistic_emitsWithoutRestFetch() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val store = createStatusStore(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            val fetchesAfterBootstrap = repository.statusFetchCount
            store.setOptimistic("/repo", "ses-1", SessionActivity.Running)
            runCurrent()
            collectJob.cancel()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)
            assertEquals(SessionActivitySource.Optimistic, emissions.last().entries["ses-1"]?.source)
            assertEquals(fetchesAfterBootstrap, repository.statusFetchCount)
        }

    @Test
    fun setOptimistic_expiresToLastServerValue() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val store = createStatusStore(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            store.setOptimistic("/repo", "ses-1", SessionActivity.Idle, ttlMs = 3_000)
            runCurrent()
            assertEquals(SessionActivity.Idle, emissions.last().entries["ses-1"]?.activity)
            assertEquals(SessionActivitySource.Optimistic, emissions.last().entries["ses-1"]?.source)
            advanceTimeBy(3_000)
            runCurrent()
            collectJob.cancel()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)
            assertEquals(SessionActivitySource.Server, emissions.last().entries["ses-1"]?.source)
        }

    @Test
    fun setOptimistic_expiresRemovesWhenNoServerValue() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val store = createStatusStore(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            store.setOptimistic("/repo", "ses-1", SessionActivity.Running, ttlMs = 10_000)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            collectJob.cancel()
            assertNull(emissions.last().entries["ses-1"])
        }

    @Test
    fun serverIdle_winsOverOptimistic() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            store.setOptimistic("/repo", "ses-1", SessionActivity.Running)
            runCurrent()
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            runCurrent()
            collectJob.cancel()
            assertTrue(emissions.last().entries.isEmpty())
        }

    @Test
    fun loaderSnapshot_removesStaleServerKeepsYoungOptimistic() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-old", raw = mapOf("type" to "busy"))),
                )
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            store.setOptimistic("/repo", "ses-new", SessionActivity.Running)
            runCurrent()
            repository.statuses = emptyList()
            eventStream.openGap()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(350)
            runCurrent()
            collectJob.cancel()
            assertNull(emissions.last().entries["ses-old"])
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-new"]?.activity)
            assertEquals(SessionActivitySource.Optimistic, emissions.last().entries["ses-new"]?.source)
        }

    @Test
    fun runningReconcile_reloadsWhileBusyAndForegrounded() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val eventStream = FakeForegroundEventStream()
            val store =
                createStatusStore(
                    repository,
                    eventStream,
                    wait = { delay(it) },
                )
            val collectJob = launch { store.observeActivity("/repo").collect { } }
            runCurrent()
            val afterSeed = repository.statusFetchCount
            advanceTimeBy(LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS)
            runCurrent()
            collectJob.cancel()
            runCurrent()
            assertTrue(repository.statusFetchCount > afterSeed)
        }

    @Test
    fun runningReconcile_skipsWhenEverythingIdle() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val store = createStatusStore(repository, FakeForegroundEventStream(), wait = { delay(it) })
            val collectJob = launch { store.observeActivity("/repo").collect { } }
            advanceUntilIdle()
            val afterSeed = repository.statusFetchCount
            advanceTimeBy(LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS)
            runCurrent()
            collectJob.cancel()
            assertEquals(afterSeed, repository.statusFetchCount)
        }

    @Test
    fun runningReconcile_skipsWhenBackgrounded() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val eventStream = FakeForegroundEventStream()
            val world = FakeWorldSignals()
            val store = createStatusStore(repository, eventStream, world, wait = { delay(it) })
            val collectJob = launch { store.observeActivity("/repo").collect { } }
            runCurrent()
            val afterSeed = repository.statusFetchCount
            world.emit(WorldSignal.BackgroundStop)
            runCurrent()
            advanceTimeBy(LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS)
            runCurrent()
            collectJob.cancel()
            runCurrent()
            assertEquals(afterSeed, repository.statusFetchCount)
        }

    @Test
    fun runningReconcile_skipsWhenCannotCatchUp() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream, wait = { delay(it) })
            val collectJob = launch { store.observeActivity("/repo").collect { } }
            runCurrent()
            val afterSeed = repository.statusFetchCount
            eventStream.setConnectionState(EventStreamConnectionState.Connecting)
            runCurrent()
            advanceTimeBy(LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS)
            runCurrent()
            collectJob.cancel()
            runCurrent()
            assertEquals(afterSeed, repository.statusFetchCount)
        }

    @Test
    fun runningReconcile_emptyResultKeepsRunningUntilConfirmed() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val store =
                createStatusStore(
                    repository,
                    FakeForegroundEventStream(),
                    wait = { delay(it) },
                )
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            runCurrent()
            val afterSeed = repository.statusFetchCount
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)

            repository.statuses = emptyList()
            advanceTimeBy(LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS)
            runCurrent()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)

            advanceTimeBy(LiveStoreRefreshPolicy.STATUS_IDLE_CONFIRM_MS)
            runCurrent()
            collectJob.cancel()
            runCurrent()
            assertNull(emissions.last().entries["ses-1"])
            assertEquals(afterSeed + 2, repository.statusFetchCount)
        }

    @Test
    fun runningReconcile_sessionReappearsClearsUnconfirmed() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val store =
                createStatusStore(
                    repository,
                    FakeForegroundEventStream(),
                    wait = { delay(it) },
                )
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            runCurrent()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)

            repository.statuses = emptyList()
            advanceTimeBy(LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS)
            runCurrent()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)

            repository.statuses =
                listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy")))
            advanceTimeBy(LiveStoreRefreshPolicy.STATUS_IDLE_CONFIRM_MS)
            runCurrent()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)

            advanceTimeBy(LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS)
            runCurrent()
            advanceTimeBy(LiveStoreRefreshPolicy.STATUS_IDLE_CONFIRM_MS)
            runCurrent()
            collectJob.cancel()
            runCurrent()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)
        }

    @Test
    fun authoritativeRefresh_emptyResultClearsRunning() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val store = createStatusStore(repository, FakeForegroundEventStream())
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)

            repository.statuses = emptyList()
            store.invalidate("/repo")
            advanceUntilIdle()
            collectJob.cancel()
            assertNull(emissions.last().entries["ses-1"])
        }

    @Test
    fun statusEventDuringLoad_isNotOverwrittenAndDoesNotRefetch() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            repository.statusDelayMs = 1_000
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            runCurrent()
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            advanceTimeBy(1_000)
            advanceUntilIdle()
            collectJob.cancel()
            assertNull(emissions.last().entries["ses-1"])
            assertEquals(1, repository.statusFetchCount)
        }

    @Test
    fun statusEventDuringLoad_busyEventPreservedWhenRestEmpty() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            repository.statusDelayMs = 1_000
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            runCurrent()
            eventStream.emit(busyStatusEvent("ses-2"))
            advanceTimeBy(1_000)
            advanceUntilIdle()
            collectJob.cancel()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-2"]?.activity)
            assertEquals(1, repository.statusFetchCount)
        }

    @Test
    fun parsesRetryPayloadFromStatusEvent() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            eventStream.emit(
                OpencodeEvent(
                    type = "session.status",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put("sessionID", "ses-1")
                            putJsonObject("status") {
                                put("type", "retry")
                                put("attempt", 2)
                            }
                        },
                ),
            )
            advanceUntilIdle()
            collectJob.cancel()
            val activity = emissions.last().entries["ses-1"]?.activity as SessionActivity.Retrying
            assertEquals(2, activity.attempt)
        }

    @Test
    fun assistantCompleted_clearsOptimisticRunningOnly() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            store.setOptimistic("/repo", "ses-1", SessionActivity.Running)
            runCurrent()
            eventStream.emit(
                OpencodeEvent(
                    type = "message.updated",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            putJsonObject("info") {
                                put("id", "msg-2")
                                put("role", "assistant")
                                putJsonObject("time") { put("completed", 1L) }
                            }
                        },
                ),
            )
            advanceUntilIdle()
            collectJob.cancel()
            assertNull(emissions.last().entries["ses-1"])
        }

    @Test
    fun setOptimistic_beforeObserve_appearsOnSubscribe() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val store = createStatusStore(repository, FakeForegroundEventStream())
            store.setOptimistic("/repo", "ses-1", SessionActivity.Running)
            val snapshot = store.observeActivity("/repo").first()
            assertEquals(SessionActivity.Running, snapshot.entries["ses-1"]?.activity)
            assertEquals(SessionActivitySource.Optimistic, snapshot.entries["ses-1"]?.source)
        }

    @Test
    fun observeActivity_seedsCachedBeforeLoader() =
        runTest(testDispatcher) {
            val cache =
                rememberedActivityCache(
                    observedAtMs = 1_000L,
                )
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            repository.statusDelayMs = 10_000
            val store = createStatusStore(repository, FakeForegroundEventStream(), sessionCache = cache)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            runCurrent()
            val cached = emissions.first { it.fromCache }
            assertFalse(cached.seeded)
            assertTrue(cached.fromCache)
            assertEquals(SessionActivity.Running, cached.entries["ses-1"]?.activity)
            assertEquals(SessionActivitySource.Cached, cached.entries["ses-1"]?.source)
            advanceTimeBy(10_000)
            advanceUntilIdle()
            collectJob.cancel()
            val loaded = emissions.last()
            assertTrue(loaded.seeded)
            assertFalse(loaded.fromCache)
            assertEquals(SessionActivitySource.Server, loaded.entries["ses-1"]?.source)
        }

    @Test
    fun observeActivity_absentFromServerDropsCached() =
        runTest(testDispatcher) {
            val cache =
                rememberedActivityCache(
                    observedAtMs = 1_000L,
                )
            val repository = FakeSessionRepository(statuses = emptyList())
            val store = createStatusStore(repository, FakeForegroundEventStream(), sessionCache = cache)
            val snapshot = store.observeActivity("/repo").first { it.seeded }
            assertNull(snapshot.entries["ses-1"])
            assertFalse(snapshot.fromCache)
        }

    @Test
    fun observeActivity_sseReplacesCachedEntry() =
        runTest(testDispatcher) {
            val cache =
                rememberedActivityCache(
                    observedAtMs = 1_000L,
                )
            val repository = FakeSessionRepository(statuses = emptyList())
            repository.statusDelayMs = 10_000
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream, sessionCache = cache)
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            runCurrent()
            assertEquals(SessionActivitySource.Cached, emissions.last().entries["ses-1"]?.source)
            eventStream.emit(busyStatusEvent("ses-1"))
            runCurrent()
            collectJob.cancel()
            assertEquals(SessionActivitySource.Server, emissions.last().entries["ses-1"]?.source)
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)
        }

    @Test
    fun clearCache_doesNotDropPersistedActivity() =
        runTest(testDispatcher) {
            val cache =
                rememberedActivityCache(
                    observedAtMs = 1_000L,
                )
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            repository.statusDelayMs = 10_000
            val store = createStatusStore(repository, FakeForegroundEventStream(), sessionCache = cache)
            store.clearCache()
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob = launch { store.observeActivity("/repo").collect { emissions.add(it) } }
            runCurrent()
            assertEquals(SessionActivitySource.Cached, emissions.first().entries["ses-1"]?.source)
            collectJob.cancel()
        }

    @Test
    fun observeActivity_remembersServerSnapshot() =
        runTest(testDispatcher) {
            val cache =
                DefaultSessionCache(
                    store = InMemorySessionCacheStore(),
                    connectionIdProvider = { "profile-1" },
                    scope = backgroundScope,
                    json = Json { ignoreUnknownKeys = true },
                    clock = { testDispatcher.scheduler.currentTime },
                    activityDebounceMs = 1_000L,
                )
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val store = createStatusStore(repository, FakeForegroundEventStream(), sessionCache = cache)
            val collectJob = launch { store.observeActivity("/repo").collect { } }
            advanceUntilIdle()
            advanceTimeBy(1_000)
            runCurrent()
            collectJob.cancel()
            val seeded = cache.seedActivity("/repo")
            assertEquals("ses-1", seeded?.single()?.sessionId)
            assertEquals(SessionActivity.Running, seeded?.single()?.activity)
        }

    private suspend fun rememberedActivityCache(observedAtMs: Long): SessionCache {
        val cache =
            DefaultSessionCache(
                store = InMemorySessionCacheStore(),
                connectionIdProvider = { "profile-1" },
                scope = CoroutineScope(SupervisorJob() + testDispatcher),
                json = Json { ignoreUnknownKeys = true },
                clock = { observedAtMs },
                activityDebounceMs = 1_000L,
            )
        cache.rememberActivity(
            "/repo",
            listOf(SessionActivityCacheEntry("ses-1", SessionActivity.Running, observedAtMs)),
        )
        delay(1_000)
        return cache
    }

    private fun createStatusStore(
        repository: FakeSessionRepository,
        eventStream: FakeForegroundEventStream,
        worldSignals: WorldSignals = SilentWorldSignals,
        wait: suspend (Long) -> Unit = { ms ->
            if (ms < LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS) delay(ms)
        },
        sessionCache: SessionCache = NoOpSessionCache,
    ): DefaultSessionStatusLiveStore {
        if (eventStream.connectionState.value == EventStreamConnectionState.Idle) {
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
        }
        val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        return DefaultSessionStatusLiveStore(
            repository,
            eventStream,
            scope,
            EventDomainAdapter(Json { ignoreUnknownKeys = true }),
            worldSignals,
            testSyncCoordinator(
                eventStream = eventStream,
                worldSignals = worldSignals,
                scope = scope.coroutineScope,
                nowMs = { testDispatcher.scheduler.currentTime },
                wait = { delay(1) },
            ),
            sessionCache = sessionCache,
            nowMs = { testDispatcher.scheduler.currentTime },
            wait = wait,
        )
    }
}

private fun busyStatusEvent(sessionId: String) =
    OpencodeEvent(
        type = "session.status",
        sessionId = sessionId,
        rawProperties =
            buildJsonObject {
                put("sessionID", sessionId)
                putJsonObject("status") { put("type", "busy") }
            },
    )

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
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val store =
                DefaultSessionLiveStore(
                    repository,
                    eventStream,
                    LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                    sessionCache = NoOpSessionCache,
                    syncCoordinator =
                        testSyncCoordinator(
                            eventStream = eventStream,
                            worldSignals = SilentWorldSignals,
                            scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher),
                            nowMs = { testDispatcher.scheduler.currentTime },
                            wait = { delay(1) },
                        ),
                )
            val emissions = mutableListOf<List<Session>>()
            val collectJob =
                launch {
                    store.observeSessions("/repo").collect { emissions.add(it.sessions) }
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
    fun observeSessions_refreshesOnV2ArchiveAndUnarchive() =
        runTest(testDispatcher) {
            val first = testSession(id = "ses-1", title = "First")
            val second = testSession(id = "ses-2", title = "Second")
            val repository = FakeSessionRepository(sessions = listOf(first, second))
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val store = sessionStore(repository, eventStream, ServerProtocol.V2)
            val emissions = mutableListOf<List<Session>>()
            val collectJob = launch { store.observeSessions("/repo").collect { emissions.add(it.sessions) } }
            advanceUntilIdle()

            // The v2 Home list asks for `archived=false`, so an archived session drops out of the refetch.
            repository.sessions = listOf(first)
            eventStream.emit(OpencodeEvent(type = "session.archived", sessionId = "ses-2"))
            advanceTimeBy(550)
            advanceUntilIdle()
            val afterArchive = emissions.last().map { it.id }

            repository.sessions = listOf(first, second)
            eventStream.emit(OpencodeEvent(type = "session.unarchived", sessionId = "ses-2"))
            advanceTimeBy(550)
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(listOf("ses-1"), afterArchive)
            assertEquals(listOf("ses-1", "ses-2"), emissions.last().map { it.id })
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
                    sessionCache = NoOpSessionCache,
                    syncCoordinator =
                        testSyncCoordinator(
                            eventStream = eventStream,
                            worldSignals = SilentWorldSignals,
                            scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher),
                            nowMs = { testDispatcher.scheduler.currentTime },
                            wait = { delay(1) },
                        ),
                )
            val emissions = mutableListOf<List<Session>>()
            val collectJob =
                launch {
                    store.observeSessions("/repo").collect { emissions.add(it.sessions) }
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

    @Test
    fun observeSessions_publishesLoadFailureUntilALaterLoadSucceeds() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    sessions = listOf(testSession(id = "ses-1", title = "First")),
                    shouldFailList = true,
                )
            val eventStream = FakeForegroundEventStream()
            val store = sessionStore(repository, eventStream, ServerProtocol.V1)
            val emissions = mutableListOf<SessionListSnapshot>()
            val collectJob = launch { store.observeSessions("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()

            val failed = emissions.single()
            assertTrue(failed.loadFailed)
            assertTrue(failed.isFromCache)
            assertTrue(failed.sessions.isEmpty())

            repository.shouldFailList = false
            store.invalidate("/repo")
            advanceUntilIdle()
            collectJob.cancel()

            val loaded = emissions.last()
            assertFalse(loaded.loadFailed)
            assertFalse(loaded.isFromCache)
            assertEquals(listOf("ses-1"), loaded.sessions.map { it.id })
        }

    @Test
    fun sessionViewed_patchesViewedMonotonicallyWithoutRefetch() =
        runTest(testDispatcher) {
            val unread = testSession(id = "ses-1", title = "First").copy(idleAtEpochMs = 30L, viewedAtEpochMs = 10L)
            val repository = FakeSessionRepository(sessions = listOf(unread))
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val store = sessionStore(repository, eventStream, ServerProtocol.V2)
            val emissions = mutableListOf<List<Session>>()
            val collectJob = launch { store.observeSessions("/repo").collect { emissions.add(it.sessions) } }
            advanceUntilIdle()
            val fetchesBefore = repository.listSessionsCallCount
            assertTrue(emissions.last().single().isServerUnread)

            eventStream.emit(viewedEvent("ses-1", idle = 30L))
            advanceTimeBy(550)
            advanceUntilIdle()
            assertEquals(30L, emissions.last().single().viewedAtEpochMs)
            assertFalse(emissions.last().single().isServerUnread)
            val emissionsAfterPatch = emissions.size

            eventStream.emit(viewedEvent("ses-1", idle = 20L))
            advanceTimeBy(550)
            advanceUntilIdle()
            assertEquals(emissionsAfterPatch, emissions.size)
            assertEquals(30L, emissions.last().single().viewedAtEpochMs)
            assertEquals(fetchesBefore, repository.listSessionsCallCount)

            // A REST list that has not caught up yet must not bring the unread state back.
            eventStream.emit(OpencodeEvent(type = "session.updated", sessionId = "ses-1"))
            advanceTimeBy(550)
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(fetchesBefore + 1, repository.listSessionsCallCount)
            assertEquals(30L, emissions.last().single().viewedAtEpochMs)
        }

    @Test
    fun applySessionViewed_patchesListAndKnownSession() =
        runTest(testDispatcher) {
            val unread = testSession(id = "ses-1", title = "First").copy(idleAtEpochMs = 30L)
            val repository = FakeSessionRepository(sessions = listOf(unread))
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val store = sessionStore(repository, eventStream, ServerProtocol.V2)
            val emissions = mutableListOf<List<Session>>()
            val known = mutableListOf<Session?>()
            val collectJob = launch { store.observeSessions("/repo").collect { emissions.add(it.sessions) } }
            val knownJob = launch { store.observeKnownSession("ses-1").collect { known.add(it) } }
            advanceUntilIdle()

            store.applySessionViewed("ses-1", 30L)
            advanceUntilIdle()
            collectJob.cancel()
            knownJob.cancel()

            assertEquals(30L, emissions.last().single().viewedAtEpochMs)
            assertEquals(30L, known.last()?.viewedAtEpochMs)
            assertEquals(1, repository.listSessionsCallCount)
        }

    @Test
    fun runEnd_refetchesListOnV2Only() =
        runTest(testDispatcher) {
            for (protocol in listOf(ServerProtocol.V1, ServerProtocol.V2)) {
                val repository = FakeSessionRepository(sessions = listOf(testSession(id = "ses-1", title = "First")))
                val eventStream = FakeForegroundEventStream()
                eventStream.setConnectionState(EventStreamConnectionState.Connected)
                val store = sessionStore(repository, eventStream, protocol)
                val collectJob = launch { store.observeSessions("/repo").collect { } }
                advanceUntilIdle()
                val fetchesBefore = repository.listSessionsCallCount

                eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
                advanceTimeBy(550)
                advanceUntilIdle()
                collectJob.cancel()

                val expected = if (protocol == ServerProtocol.V2) fetchesBefore + 1 else fetchesBefore
                assertEquals("protocol=$protocol", expected, repository.listSessionsCallCount)
            }
        }

    private fun sessionStore(
        repository: FakeSessionRepository,
        eventStream: FakeForegroundEventStream,
        protocol: ServerProtocol,
    ): DefaultSessionLiveStore =
        DefaultSessionLiveStore(
            repository,
            eventStream,
            LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
            sessionCache = NoOpSessionCache,
            syncCoordinator =
                testSyncCoordinator(
                    eventStream = eventStream,
                    worldSignals = SilentWorldSignals,
                    scope = CoroutineScope(SupervisorJob() + testDispatcher),
                    nowMs = { testDispatcher.scheduler.currentTime },
                    wait = { delay(1) },
                ),
            eventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
            capabilities = { ServerCapabilities.forProtocol(protocol) },
        )

    private fun viewedEvent(
        sessionId: String,
        idle: Long,
    ): OpencodeEvent =
        OpencodeEvent(
            type = "session.viewed",
            sessionId = sessionId,
            rawProperties =
                buildJsonObject {
                    put("sessionID", sessionId)
                    put("idle", idle)
                },
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveObservationTeardownTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun profileSwitch_readyStoreResubscribes_andStillFlagsReady() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val eventStream = FakeForegroundEventStream()
            val statusStore = createStatusStore(repository, eventStream)
            val readyStore = createReadyStore(statusStore, eventStream)
            readyStore.bindProfile("profile-a")
            var homeJob = observeHome(statusStore, readyStore, mutableListOf())
            advanceUntilIdle()

            homeJob.cancel()
            readyStore.bindProfile("profile-b")
            statusStore.clearCache()
            val readyEmissions = mutableListOf<Set<String>>()
            homeJob = observeHome(statusStore, readyStore, readyEmissions)
            advanceUntilIdle()

            readyStore.notifyPromptSent("ses-1", "/repo")
            eventStream.emit(busyStatusEvent("ses-1"))
            runCurrent()
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            runCurrent()
            homeJob.cancel()

            assertTrue(readyEmissions.last().contains("ses-1"))
        }

    @Test
    fun profileSwitch_doesNotDoubleStatusUpstreams() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val statusStore = createStatusStore(repository, eventStream)
            val readyStore = createReadyStore(statusStore, eventStream)
            readyStore.bindProfile("profile-a")
            var homeJob = observeHome(statusStore, readyStore, mutableListOf())
            advanceUntilIdle()

            eventStream.openGap()
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(300L + 50)
            advanceUntilIdle()
            val fetchesPerEventBefore = repository.statusFetchCount
            eventStream.openGap()
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(300L + 50)
            advanceUntilIdle()
            val fetchesPerEvent = repository.statusFetchCount - fetchesPerEventBefore

            homeJob.cancel()
            readyStore.bindProfile("profile-b")
            statusStore.clearCache()
            homeJob = observeHome(statusStore, readyStore, mutableListOf())
            advanceUntilIdle()

            val fetchesAfterSwitch = repository.statusFetchCount
            eventStream.openGap()
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(300L + 50)
            advanceUntilIdle()
            val fetchesPerEventAfterSwitch = repository.statusFetchCount - fetchesAfterSwitch
            homeJob.cancel()

            assertEquals(1, fetchesPerEvent)
            assertEquals(fetchesPerEvent, fetchesPerEventAfterSwitch)
            assertEquals(1, statusStore.activeUpstreamCount())
        }

    private fun CoroutineScope.observeHome(
        statusStore: SessionStatusLiveStore,
        readyStore: SessionReadyStore,
        readyEmissions: MutableList<Set<String>>,
    ) = launch {
        combine(
            statusStore.observeActivity("/repo"),
            readyStore.observeReadyIds("/repo"),
        ) { _, readyIds -> readyIds }
            .collect { readyEmissions.add(it) }
    }

    private fun createStatusStore(
        repository: FakeSessionRepository,
        eventStream: FakeForegroundEventStream,
    ): DefaultSessionStatusLiveStore =
        DefaultSessionStatusLiveStore(
            repository,
            eventStream,
            LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
            EventDomainAdapter(Json { ignoreUnknownKeys = true }),
            SilentWorldSignals,
            testSyncCoordinator(
                eventStream = eventStream,
                worldSignals = SilentWorldSignals,
                scope = CoroutineScope(SupervisorJob() + testDispatcher),
                nowMs = { testDispatcher.scheduler.currentTime },
                wait = { delay(1) },
            ),
        )

    private fun createReadyStore(
        statusStore: SessionStatusLiveStore,
        eventStream: FakeForegroundEventStream,
    ): DefaultSessionReadyStore =
        DefaultSessionReadyStore(
            sessionStatusLiveStore = statusStore,
            eventStream = eventStream,
            liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
            persistence = InMemorySessionReadyPersistence(),
            clock = SessionReadyClock { 1_000_000L },
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
class InvalidationAckTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun invalidate_returnsWithoutWaitingOutTheCeiling_whenObservationIsTornDownMidRefresh() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(sessions = listOf(testSession("1", "Alpha")))
            val sessionCache = GatedSessionCache()
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val store =
                DefaultSessionLiveStore(
                    repository,
                    eventStream,
                    LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                    sessionCache = sessionCache,
                    syncCoordinator =
                        testSyncCoordinator(
                            eventStream = eventStream,
                            worldSignals = SilentWorldSignals,
                            scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher),
                            nowMs = { testDispatcher.scheduler.currentTime },
                            wait = { delay(1) },
                        ),
                )
            val observer = launch { store.observeSessions("/repo").collect { } }
            advanceUntilIdle()

            val gate = CompletableDeferred<Unit>()
            sessionCache.nextRememberGate = gate
            var returned = false
            val invalidateJob =
                launch {
                    store.invalidate(null)
                    returned = true
                }
            // runCurrent, not advanceUntilIdle: advancing the clock would let the ack
            // ceiling fire and hide whether the cancelled collector acked on its own.
            runCurrent()
            assertFalse("invalidate must still be waiting while its refresh is in flight", returned)

            val tornDownAt = testScheduler.currentTime
            store.clearCache()
            runCurrent()
            val elapsed = testScheduler.currentTime - tornDownAt
            observer.cancel()
            invalidateJob.cancel()

            assertTrue("invalidate must return after its collector was cancelled mid-refresh", returned)
            assertTrue(
                "the cancelled collector must ack from its finally, not leave the caller " +
                    "waiting out the ${LiveStoreRefreshPolicy.INVALIDATION_ACK_TIMEOUT_MS} ms ceiling (elapsed=$elapsed ms)",
                elapsed < LiveStoreRefreshPolicy.INVALIDATION_ACK_TIMEOUT_MS,
            )
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveStoreRevisionGateTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun statusReload_isDiscarded_whenSessionIdleLandsWhileTheGetIsInFlight() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val store =
                DefaultSessionStatusLiveStore(
                    repository,
                    eventStream,
                    LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                    EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    SilentWorldSignals,
                    testSyncCoordinator(
                        eventStream = eventStream,
                        worldSignals = SilentWorldSignals,
                        scope = CoroutineScope(SupervisorJob() + testDispatcher),
                        nowMs = { testDispatcher.scheduler.currentTime },
                        wait = { delay(1) },
                    ),
                )
            val emissions = mutableListOf<SessionActivitySnapshot>()
            val collectJob =
                launch {
                    store.observeActivity("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()
            assertEquals(SessionActivity.Running, emissions.last().entries["ses-1"]?.activity)

            repository.statusDelayMs = 10_000
            eventStream.openGap()
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(300L + 50)
            assertEquals(
                "the reconnect reload must be in flight before the idle event lands",
                2,
                repository.statusFetchCount,
            )

            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            runCurrent()
            advanceUntilIdle()
            collectJob.cancel()

            assertTrue(
                "session.idle must outrank the status GET that was already in flight: the " +
                    "pre-idle REST snapshot put ses-1 back and the row is stuck busy " +
                    "(last emission: ${emissions.last()})",
                emissions.last().entries["ses-1"] == null,
            )
        }

    @Test
    fun discardedStatusReload_doesNotClearStale_soLaterCatchUpStillRuns() =
        runTest(testDispatcher) {
            val world = FakeWorldSignals()
            val repository =
                FakeSessionRepository(
                    statuses = listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val store =
                DefaultSessionStatusLiveStore(
                    repository,
                    eventStream,
                    LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                    EventDomainAdapter(Json { ignoreUnknownKeys = true }),
                    world,
                    testSyncCoordinator(
                        eventStream = eventStream,
                        worldSignals = world,
                        scope = CoroutineScope(SupervisorJob() + testDispatcher),
                        nowMs = { testDispatcher.scheduler.currentTime },
                        wait = { delay(1) },
                    ),
                )
            val collectJob = launch { store.observeActivity("/repo").collect { } }
            advanceUntilIdle()
            assertEquals(1, repository.statusFetchCount)

            world.emit(WorldSignal.BackgroundStop)
            runCurrent()
            repository.statusDelayMs = 10_000
            world.emit(WorldSignal.Foreground)
            runCurrent()
            assertEquals(2, repository.statusFetchCount)

            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            runCurrent()
            advanceUntilIdle()

            repository.statusDelayMs = 0
            repository.statuses =
                listOf(SessionStatus(sessionId = "ses-2", raw = mapOf("type" to "busy")))
            val fetchesAfterDiscard = repository.statusFetchCount
            store.invalidate("/repo")
            runCurrent()
            advanceUntilIdle()
            collectJob.cancel()

            assertTrue(
                "invalidate after a raced status event must still refresh",
                repository.statusFetchCount > fetchesAfterDiscard,
            )
        }

    @Test
    fun permissionRefreshes_completingOutOfOrder_endWithTheNewestList() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            repository.permissionScript += 0L to emptyList<PermissionRequest>()
            repository.permissionScript += 5_000L to emptyList<PermissionRequest>()
            repository.permissionScript += 0L to listOf(testPermissionRequest("perm-2"))
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val store =
                DefaultPermissionLiveStore(
                    repository,
                    eventStream,
                    LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                    DiagnosticLogger(DiagnosticLogBuffer(clockEpochMilliseconds = { 0L })),
                    testSyncCoordinator(
                        eventStream = eventStream,
                        worldSignals = SilentWorldSignals,
                        scope = CoroutineScope(SupervisorJob() + testDispatcher),
                        nowMs = { testDispatcher.scheduler.currentTime },
                        wait = { delay(1) },
                    ),
                )
            val emissions = mutableListOf<List<PermissionRequest>>()
            val collectJob =
                launch {
                    store.observePermissions("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()
            assertEquals(1, repository.permissionFetchCount)

            eventStream.emit(OpencodeEvent(type = "permission.replied", sessionId = "ses-1"))
            // runCurrent, not advanceUntilIdle: the stalled GET must still be in flight.
            runCurrent()
            assertEquals(
                "the permission.replied refresh must be in flight before the second trigger",
                2,
                repository.permissionFetchCount,
            )

            store.invalidate("/repo")
            runCurrent()
            assertEquals(
                "the second refresh must collapse onto the in-flight one rather than racing it",
                2,
                repository.permissionFetchCount,
            )

            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(
                "the newest permission list must be the last one emitted; the stale empty " +
                    "response overwrote it and the prompt is gone " +
                    "(emissions: ${emissions.map { requests -> requests.map { it.id } }})",
                listOf("perm-2"),
                emissions.last().map { it.id },
            )
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPermissionLiveStoreTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun store(
        repository: FakeSessionRepository,
        eventStream: FakeForegroundEventStream,
        scope: CoroutineScope,
    ): DefaultPermissionLiveStore {
        eventStream.setConnectionState(EventStreamConnectionState.Connected)
        return DefaultPermissionLiveStore(
            repository,
            eventStream,
            LiveStoreScope(scope),
            DiagnosticLogger(DiagnosticLogBuffer(clockEpochMilliseconds = { 0L })),
            testSyncCoordinator(
                eventStream = eventStream,
                worldSignals = SilentWorldSignals,
                scope = scope,
                nowMs = { testDispatcher.scheduler.currentTime },
                wait = { delay(1) },
            ),
        )
    }

    @Test
    fun permissionBurst_isNeitherDelayedNorAmplified() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            val eventStream = FakeForegroundEventStream()
            val store = store(repository, eventStream, CoroutineScope(SupervisorJob() + testDispatcher))
            val emissions = mutableListOf<List<PermissionRequest>>()
            val collectJob = launch { store.observePermissions("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            assertEquals(1, repository.permissionFetchCount)
            val emissionsAfterSubscribe = emissions.size

            repository.permissions = listOf(testPermissionRequest("perm-1"))
            eventStream.emit(OpencodeEvent(type = "permission.asked", sessionId = "ses-1"))
            // runCurrent only: no virtual time is advanced, so a debounced refresh would
            // still be parked and the prompt would not be on screen yet.
            runCurrent()
            assertEquals(
                "permission.asked must refresh immediately, not behind a debounce window",
                2,
                repository.permissionFetchCount,
            )
            assertEquals(
                "the first prompt must already be emitted without advancing time",
                listOf("perm-1"),
                emissions.last().map { it.id },
            )

            repository.permissions = listOf(testPermissionRequest("perm-1"), testPermissionRequest("perm-2"))
            eventStream.emit(OpencodeEvent(type = "permission.asked", sessionId = "ses-1"))
            repository.permissions =
                listOf(
                    testPermissionRequest("perm-1"),
                    testPermissionRequest("perm-2"),
                    testPermissionRequest("perm-3"),
                )
            eventStream.emit(OpencodeEvent(type = "permission.asked", sessionId = "ses-1"))
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(
                "subscribe plus the first prompt GET, then the in-flight burst coalesces",
                3,
                repository.permissionFetchCount,
            )
            assertEquals(
                "the burst must land as the newest list, not a stale intermediate one",
                listOf("perm-1", "perm-2", "perm-3"),
                emissions.last().map { it.id },
            )
            assertTrue(
                "the burst must reach the collector (emissions: " +
                    "${emissions.map { requests -> requests.map { it.id } }})",
                emissions.size > emissionsAfterSubscribe,
            )
        }

    @Test
    fun indexedPermissionEvent_refreshesOnlyTheOwningDirectory() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            val approval = testPermissionRequest("perm-a").copy(sessionId = "ses-a")
            repository.permissionsByDirectory =
                mapOf(
                    "/repo-a" to listOf(approval),
                    "/repo-b" to emptyList(),
                )
            val eventStream = FakeForegroundEventStream()
            val store = store(repository, eventStream, CoroutineScope(SupervisorJob() + testDispatcher))
            store.noteSessions("/repo-a", setOf("ses-a"))
            store.noteSessions("/repo-b", setOf("ses-b"))
            val jobA = launch { store.observePermissions("/repo-a").collect { } }
            val jobB = launch { store.observePermissions("/repo-b").collect { } }
            advanceUntilIdle()
            assertEquals(1, repository.permissionDirectories.count { it == "/repo-a" })
            assertEquals(1, repository.permissionDirectories.count { it == "/repo-b" })

            eventStream.emit(OpencodeEvent(type = "permission.asked", sessionId = "ses-a"))
            advanceUntilIdle()
            assertEquals(2, repository.permissionDirectories.count { it == "/repo-a" })
            assertEquals(1, repository.permissionDirectories.count { it == "/repo-b" })

            // The session left the home list, but the open request still belongs to this project.
            store.noteSessions("/repo-a", emptySet())
            eventStream.emit(OpencodeEvent(type = "permission.replied", sessionId = "ses-a"))
            advanceUntilIdle()
            assertEquals(3, repository.permissionDirectories.count { it == "/repo-a" })
            assertEquals(1, repository.permissionDirectories.count { it == "/repo-b" })

            eventStream.emit(OpencodeEvent(type = "permission.asked", sessionId = "ses-other"))
            advanceUntilIdle()
            assertEquals(3, repository.permissionDirectories.count { it == "/repo-a" })
            assertEquals(1, repository.permissionDirectories.count { it == "/repo-b" })

            val jobC = launch { store.observePermissions("/repo-c").collect { } }
            advanceUntilIdle()
            assertEquals(1, repository.permissionDirectories.count { it == "/repo-c" })
            eventStream.emit(OpencodeEvent(type = "permission.asked", sessionId = "ses-other"))
            advanceUntilIdle()
            jobA.cancel()
            jobB.cancel()
            jobC.cancel()

            assertEquals(
                "an unindexed directory still refetches on any permission event",
                2,
                repository.permissionDirectories.count { it == "/repo-c" },
            )
            assertEquals(3, repository.permissionDirectories.count { it == "/repo-a" })
            assertEquals(1, repository.permissionDirectories.count { it == "/repo-b" })
        }

    @Test
    fun reconnectPair_coalescesIntoOneRefresh() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            val eventStream = FakeForegroundEventStream()
            val store = store(repository, eventStream, CoroutineScope(SupervisorJob() + testDispatcher))
            val collectJob = launch { store.observePermissions("/repo").collect { } }
            advanceUntilIdle()
            assertEquals(1, repository.permissionFetchCount)

            eventStream.openGap()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            advanceTimeBy(1)
            runCurrent()
            val afterGap = repository.permissionFetchCount
            assertEquals(
                "a snapshot gap must reconcile immediately",
                2,
                afterGap,
            )
            eventStream.emit(OpencodeEvent(type = "stream.reconnected"))
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            runCurrent()
            assertEquals(
                "neither reconnect event may fetch before the debounce window elapses",
                afterGap,
                repository.permissionFetchCount,
            )

            advanceTimeBy(300L + 50)
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(
                "the reconnect pair must collapse into the snapshot catch-up GET",
                afterGap,
                repository.permissionFetchCount,
            )
        }

    @Test
    fun unchangedList_isNotReEmitted() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            repository.permissions = listOf(testPermissionRequest("perm-1"))
            val eventStream = FakeForegroundEventStream()
            val store = store(repository, eventStream, CoroutineScope(SupervisorJob() + testDispatcher))
            val emissions = mutableListOf<List<PermissionRequest>>()
            val collectJob = launch { store.observePermissions("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            assertEquals(1, emissions.size)

            eventStream.emit(OpencodeEvent(type = "permission.asked", sessionId = "ses-1"))
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(
                "the second refresh really must have run",
                2,
                repository.permissionFetchCount,
            )
            assertEquals(
                "an unchanged list must not be emitted again (emissions: " +
                    "${emissions.map { requests -> requests.map { it.id } }})",
                1,
                emissions.size,
            )
        }

    @Test
    fun clearCache_isolatesSameDirectoryAfterProfileSwitch() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            repository.permissions = listOf(testPermissionRequest("perm-a"))
            val eventStream = FakeForegroundEventStream()
            val store = store(repository, eventStream, CoroutineScope(SupervisorJob() + testDispatcher))
            val first = mutableListOf<List<PermissionRequest>>()
            val firstJob = launch { store.observePermissions("/repo").collect { first.add(it) } }
            advanceUntilIdle()
            assertEquals(listOf("perm-a"), first.last().map { it.id })
            firstJob.cancel()
            store.clearCache()
            repository.permissions = listOf(testPermissionRequest("perm-b"))
            val second = mutableListOf<List<PermissionRequest>>()
            val secondJob = launch { store.observePermissions("/repo").collect { second.add(it) } }
            advanceUntilIdle()
            secondJob.cancel()
            assertEquals(listOf("perm-b"), second.last().map { it.id })
            assertTrue(second.none { list -> list.any { it.id == "perm-a" } })
        }

    @Test
    fun slowPermissionRefresh_doesNotStallMessageEventDelivery() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository()
            sessionRepository.permissionScript += 5_000L to listOf(testPermissionRequest("perm-slow"))
            val messageRepository = FakeMessageRepository()
            messageRepository.messages =
                listOf(
                    Message(
                        id = "msg-1",
                        role = MessageRole.User,
                        text = "Hello",
                        parts = listOf(MessagePart.Text(text = "Hello", id = "part-1")),
                    ),
                )
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val permissionStore =
                store(sessionRepository, eventStream, CoroutineScope(SupervisorJob() + testDispatcher))
            val sync =
                MessageSync(
                    listMessages = { sessionId, directory -> messageRepository.listMessages(sessionId, directory) },
                    engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                    eventStream = eventStream,
                    liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                    syncCoordinator =
                        testSyncCoordinator(
                            eventStream = eventStream,
                            worldSignals = SilentWorldSignals,
                            scope = CoroutineScope(SupervisorJob() + testDispatcher),
                            nowMs = { testDispatcher.scheduler.currentTime },
                            wait = { delay(1) },
                        ),
                    connectionProfileIdProvider = { "local" },
                    nowMs = { testDispatcher.scheduler.currentTime },
                )
            val transcript = mutableListOf<String>()
            val permissionJob = launch { permissionStore.observePermissions("/repo").collect { } }
            val messageJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) {
                            transcript.add(update.messages.single().text)
                        }
                    }
                }
            advanceTimeBy(1)
            runCurrent()
            eventStream.emit(OpencodeEvent(type = "permission.asked", sessionId = "ses-1"))
            runCurrent()
            eventStream.emit(
                OpencodeEvent(
                    type = "message.part.delta",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put("delta", " streamed")
                            put("field", "text")
                            put("messageID", "msg-1")
                            put("partID", "part-1")
                        },
                ),
            )
            advanceTimeBy(1)
            runCurrent()
            permissionJob.cancel()
            messageJob.cancel()
            assertTrue(transcript.contains("Hello streamed"))
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultQuestionLiveStoreTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun store(
        repository: FakeSessionRepository,
        eventStream: FakeForegroundEventStream,
        scope: CoroutineScope,
        worldSignals: WorldSignals = SilentWorldSignals,
    ): DefaultQuestionLiveStore {
        eventStream.setConnectionState(EventStreamConnectionState.Connected)
        return DefaultQuestionLiveStore(
            repository,
            eventStream,
            LiveStoreScope(scope),
            DiagnosticLogger(DiagnosticLogBuffer(clockEpochMilliseconds = { 0L })),
            testSyncCoordinator(
                eventStream = eventStream,
                worldSignals = worldSignals,
                scope = scope,
                nowMs = { testDispatcher.scheduler.currentTime },
                wait = { delay(1) },
            ),
        )
    }

    @Test
    fun questionBurst_isNeitherDelayedNorAmplified() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            val eventStream = FakeForegroundEventStream()
            val store = store(repository, eventStream, CoroutineScope(SupervisorJob() + testDispatcher))
            val emissions = mutableListOf<List<QuestionRequest>>()
            val collectJob = launch { store.observeQuestions("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            assertEquals(1, repository.questionFetchCount)
            val emissionsAfterSubscribe = emissions.size

            repository.questions = listOf(testQuestionRequest("que-1"))
            eventStream.emit(OpencodeEvent(type = "question.asked", sessionId = "ses-1"))
            runCurrent()
            assertEquals(
                "question.asked must refresh immediately, not behind a debounce window",
                2,
                repository.questionFetchCount,
            )
            assertEquals(
                "the first question must already be emitted without advancing time",
                listOf("que-1"),
                emissions.last().map { it.id },
            )

            repository.questions = listOf(testQuestionRequest("que-1"), testQuestionRequest("que-2"))
            eventStream.emit(OpencodeEvent(type = "question.asked", sessionId = "ses-1"))
            repository.questions =
                listOf(
                    testQuestionRequest("que-1"),
                    testQuestionRequest("que-2"),
                    testQuestionRequest("que-3"),
                )
            eventStream.emit(OpencodeEvent(type = "question.asked", sessionId = "ses-1"))
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(
                "subscribe plus the first question GET, then the in-flight burst coalesces",
                3,
                repository.questionFetchCount,
            )
            assertEquals(
                "the burst must land as the newest list, not a stale intermediate one",
                listOf("que-1", "que-2", "que-3"),
                emissions.last().map { it.id },
            )
            assertTrue(
                "the burst must reach the collector (emissions: " +
                    "${emissions.map { requests -> requests.map { it.id } }})",
                emissions.size > emissionsAfterSubscribe,
            )
        }

    @Test
    fun reconnectPair_coalescesIntoOneRefresh() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            val eventStream = FakeForegroundEventStream()
            val store = store(repository, eventStream, CoroutineScope(SupervisorJob() + testDispatcher))
            val collectJob = launch { store.observeQuestions("/repo").collect { } }
            advanceUntilIdle()
            assertEquals(1, repository.questionFetchCount)

            eventStream.openGap()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            advanceTimeBy(1)
            runCurrent()
            val afterGap = repository.questionFetchCount
            assertEquals(
                "a snapshot gap must reconcile immediately",
                2,
                afterGap,
            )
            eventStream.emit(OpencodeEvent(type = "stream.reconnected"))
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            runCurrent()
            assertEquals(
                "neither reconnect event may fetch before the debounce window elapses",
                afterGap,
                repository.questionFetchCount,
            )

            advanceTimeBy(300L + 50)
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(
                "the reconnect pair must collapse into the snapshot catch-up GET",
                afterGap,
                repository.questionFetchCount,
            )
        }

    @Test
    fun unchangedList_isNotReEmitted() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            repository.questions = listOf(testQuestionRequest("que-1"))
            val eventStream = FakeForegroundEventStream()
            val store = store(repository, eventStream, CoroutineScope(SupervisorJob() + testDispatcher))
            val emissions = mutableListOf<List<QuestionRequest>>()
            val collectJob = launch { store.observeQuestions("/repo").collect { emissions.add(it) } }
            advanceUntilIdle()
            assertEquals(1, emissions.size)

            eventStream.emit(OpencodeEvent(type = "question.asked", sessionId = "ses-1"))
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(
                "the second refresh really must have run",
                2,
                repository.questionFetchCount,
            )
            assertEquals(
                "an unchanged list must not be emitted again (emissions: " +
                    "${emissions.map { requests -> requests.map { it.id } }})",
                1,
                emissions.size,
            )
        }

    @Test
    fun clearCache_isolatesSameDirectoryAfterProfileSwitch() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            repository.questions = listOf(testQuestionRequest("que-a"))
            val eventStream = FakeForegroundEventStream()
            val store = store(repository, eventStream, CoroutineScope(SupervisorJob() + testDispatcher))
            val first = mutableListOf<List<QuestionRequest>>()
            val firstJob = launch { store.observeQuestions("/repo").collect { first.add(it) } }
            advanceUntilIdle()
            assertEquals(listOf("que-a"), first.last().map { it.id })
            firstJob.cancel()
            store.clearCache()
            repository.questions = listOf(testQuestionRequest("que-b"))
            val second = mutableListOf<List<QuestionRequest>>()
            val secondJob = launch { store.observeQuestions("/repo").collect { second.add(it) } }
            advanceUntilIdle()
            secondJob.cancel()
            assertEquals(listOf("que-b"), second.last().map { it.id })
            assertTrue(second.none { list -> list.any { it.id == "que-a" } })
        }

    @Test
    fun stoppedOccupancy_doesNotRestOnReconnectUntilForeground() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository()
            val eventStream = FakeForegroundEventStream()
            val world = FakeWorldSignals()
            val store =
                store(
                    repository,
                    eventStream,
                    CoroutineScope(SupervisorJob() + testDispatcher),
                    world,
                )
            val collectJob = launch { store.observeQuestions("/repo").collect { } }
            advanceUntilIdle()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            runCurrent()
            val afterInitial = repository.questionFetchCount
            assertEquals(1, afterInitial)

            world.emit(WorldSignal.BackgroundStop)
            eventStream.openGap()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            eventStream.emit(OpencodeEvent(type = "stream.reconnected"))
            advanceTimeBy(300L + 50)
            advanceUntilIdle()
            assertEquals(
                "Stopped occupancy must not REST while SSE is down",
                afterInitial,
                repository.questionFetchCount,
            )

            world.emit(WorldSignal.Foreground)
            advanceUntilIdle()
            collectJob.cancel()
            assertTrue(
                "Foreground catch-up must REST the unpaid gap",
                repository.questionFetchCount > afterInitial,
            )
        }
}

private fun testPermissionRequest(id: String) =
    PermissionRequest(
        id = id,
        sessionId = "ses-1",
        permission = "bash",
        patterns = emptyList(),
        metadata = buildJsonObject { },
        always = emptyList(),
        toolMessageId = null,
        toolCallId = null,
    )

private fun testQuestionRequest(id: String) =
    QuestionRequest(
        id = id,
        sessionId = "ses-1",
        questions =
            listOf(
                QuestionInfo(
                    question = "Which approach?",
                    header = "Approach",
                    options = listOf(QuestionOption(label = "A", description = "First")),
                ),
            ),
        toolMessageId = null,
        toolCallId = null,
    )

private class GatedSessionCache : SessionCache {
    var nextRememberGate: CompletableDeferred<Unit>? = null

    override suspend fun seedSessions(worktree: String): List<Session>? = null

    override suspend fun rememberSessions(
        worktree: String,
        sessions: List<Session>,
    ) {
        nextRememberGate?.also {
            nextRememberGate = null
            it.await()
        }
    }

    override suspend fun seedActivity(directory: String): List<SessionActivityCacheEntry>? = null

    override suspend fun rememberActivity(
        directory: String,
        entries: List<SessionActivityCacheEntry>,
    ) = Unit

    override fun openMessages(
        sessionId: String,
        worktree: String,
    ): SessionMessageCacheHandle = NoOpSessionCache.openMessages(sessionId, worktree)

    override suspend fun purgeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun clearAll() = Unit

    override suspend fun estimatedSizeBytes(): Long = 0L
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
    var messagesBySession: MutableMap<String, List<Message>> = mutableMapOf(),
    var shouldFailLoad: Boolean = false,
    var shouldFailMissingSession: Boolean = false,
    private val holdLoadUntil: CompletableDeferred<Unit>? = null,
) : MessageRepository {
    var listMessagesCallCount = 0
    var nextLoadGate: CompletableDeferred<Unit>? = null
    val listHoldsEntered = AtomicInteger(0)
    val listMessagesStarted = AtomicInteger(0)

    override fun observeMessages(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> = emptyFlow()

    override fun observeDisplayMessages(
        sessionId: String,
        directory: String?,
    ): Flow<dev.opencode.mobile.domain.message.DisplayMessageListUpdate> = emptyFlow()

    override suspend fun refreshMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = listMessages(sessionId, directory)

    override suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String?,
    ): List<dev.opencode.mobile.domain.message.DisplayMessage> =
        refreshMessages(sessionId, directory).map {
            dev.opencode.mobile.domain.message
                .DisplayMessage(it)
        }

    override suspend fun prepareOptimisticUserMessage(
        id: String,
        text: String,
    ): dev.opencode.mobile.domain.message.DisplayMessage =
        dev.opencode.mobile.domain.message.DisplayMessage(
            Message(id = id, role = MessageRole.User, text = text),
        )

    override fun clearMessageCache() = Unit

    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> {
        listMessagesStarted.incrementAndGet()
        holdLoadUntil?.await()
        nextLoadGate?.also {
            nextLoadGate = null
            listHoldsEntered.incrementAndGet()
            it.await()
        }
        listMessagesCallCount += 1
        if (shouldFailMissingSession) error("NotFoundError Session not found: $sessionId")
        if (shouldFailLoad) error("load failed")
        return messagesBySession[sessionId] ?: messages
    }

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) = Unit

    override suspend fun runShellCommand(
        sessionId: String,
        options: ShellCommandOptions,
        directory: String?,
    ) = Unit
}

private class FakeSessionRepository(
    var sessions: List<Session> = emptyList(),
    var statuses: List<SessionStatus> = emptyList(),
    var todos: List<SessionTodo> = emptyList(),
    var listTodosCallCount: Int = 0,
    var statusFetchCount: Int = 0,
    var shouldFailList: Boolean = false,
    var shouldFailStatuses: Boolean = false,
) : SessionRepository {
    var listSessionsCallCount = 0
    var permissionFetchCount = 0
    val permissionDirectories = mutableListOf<String?>()
    var permissionsByDirectory: Map<String, List<PermissionRequest>> = emptyMap()
    var questionFetchCount = 0
    var nextListGate: CompletableDeferred<Unit>? = null
    var statusDelayMs: Long = 0
    val permissionScript: MutableList<Pair<Long, List<PermissionRequest>>> = mutableListOf()
    var permissions: List<PermissionRequest> = emptyList()
    val questionScript: MutableList<Pair<Long, List<QuestionRequest>>> = mutableListOf()
    var questions: List<QuestionRequest> = emptyList()

    override suspend fun listSessions(directory: String?): List<Session> {
        listSessionsCallCount += 1
        nextListGate?.also {
            nextListGate = null
            it.await()
        }
        if (shouldFailList) error("list failed")
        return sessions
    }

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ): Session = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?): List<SessionStatus> {
        statusFetchCount += 1
        if (statusDelayMs > 0) delay(statusDelayMs)
        if (shouldFailStatuses) error("status failed")
        return statuses
    }

    override suspend fun listPendingPermissions(directory: String?): List<PermissionRequest> {
        permissionFetchCount += 1
        permissionDirectories += directory
        permissionsByDirectory[directory]?.let { return it }
        val scripted = if (permissionScript.isEmpty()) null else permissionScript.removeAt(0)
        if (scripted == null) return permissions
        delay(scripted.first)
        return scripted.second
    }

    override suspend fun listPendingQuestions(directory: String?): List<QuestionRequest> {
        questionFetchCount += 1
        val scripted = if (questionScript.isEmpty()) null else questionScript.removeAt(0)
        if (scripted == null) return questions
        delay(scripted.first)
        return scripted.second
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
    ): List<Session> = error("unused")

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
