package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.message.ConnectionEpoch
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
import dev.opencode.mobile.domain.message.MessageSyncRecoveryReason
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.message.SessionMessageEngine
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.markdown.OpenCodeSyntaxHighlighter
import dev.opencode.mobile.markdown.OpenCodeTextMateHighlightingEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
            advanceTimeBy(1)

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
            eventStream.emit(OpencodeEvent(type = "stream.reconnected", sessionId = "ses-1"))
            advanceTimeBy(50)

            assertEquals("Hello", emissions.last().single().text)
            assertTrue(sync.syncHealth("ses-1", "/repo") is MessageSyncHealth.Recovering)
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
            eventStream.emit(OpencodeEvent(type = "stream.reconnected", sessionId = "ses-1"))
            advanceTimeBy(20_000)

            assertTrue(repository.listMessagesCallCount <= callsAfterInitial + 2)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_clearCacheStopsReconnectRecovery() =
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

            repository.shouldFailLoad = true
            sync.clearCache()
            eventStream.emit(OpencodeEvent(type = "stream.reconnected", sessionId = "ses-1"))
            advanceTimeBy(20_000)

            assertEquals(callsAfterInitial, repository.listMessagesCallCount)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_eventDuringRecoveryKeepsRecoveringUntilQuietAuthority() =
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

            val recoveryGate = CompletableDeferred<Unit>()
            repository.nextLoadGate = recoveryGate
            eventStream.emit(OpencodeEvent(type = "stream.reconnected", sessionId = "ses-1"))
            // Start recovery only — do not advanceUntilIdle while the REST gate is held.
            advanceTimeBy(1)
            assertTrue(sync.syncHealth("ses-1", "/repo") is MessageSyncHealth.Recovering)

            // Inject a relevant race while the authority window is open.
            eventStream.emit(messageUpdatedEvent("msg-race", "assistant"))
            advanceTimeBy(1)

            repository.messages = listOf(Message(id = "msg-final", role = MessageRole.Assistant, text = "final"))
            recoveryGate.complete(Unit)
            // First attempt closes raced; backoff(1) then a quiet reconcile.
            advanceTimeBy(50)

            assertEquals("final", emissions.last().single().text)
            assertEquals(MessageSyncHealth.Healthy, sync.syncHealth("ses-1", "/repo"))
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_repeatedOverflowCycles_recoverToHealthyOnlyAfterValidatedReplace() =
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
                    pollIntervalMs = 60_000L,
                    eventStagingCapacity = 64,
                    eventStagingByteCapacity = 200,
                    recoveryDelay = { delay(1) },
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

            repeat(2) { cycle ->
                val recoveredText = "recovered-$cycle"
                repository.shouldFailLoad = true
                repeat(8) {
                    eventStream.emit(
                        partDeltaEvent(
                            partId = "prt-$cycle",
                            messageId = "msg-1",
                            delta = "x".repeat(80),
                        ),
                    )
                }
                advanceTimeBy(50)
                assertTrue(
                    "cycle $cycle should enter recovering after overflow",
                    sync.syncHealth("ses-1", "/repo") is MessageSyncHealth.Recovering,
                )
                assertEquals(
                    if (cycle == 0) "Hello" else "recovered-${cycle - 1}",
                    emissions.last().single().text,
                )

                repository.messages =
                    listOf(
                        Message(
                            id = "msg-recovered-$cycle",
                            role = MessageRole.Assistant,
                            text = recoveredText,
                        ),
                    )
                repository.shouldFailLoad = false
                advanceTimeBy(50)
                assertEquals(recoveredText, emissions.last().single().text)
                assertEquals(MessageSyncHealth.Healthy, sync.syncHealth("ses-1", "/repo"))
            }

            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_rapidSessionSwitchAtoBtoA_noCrossSessionPublish() =
        runTest(testDispatcher) {
            val repository =
                FakeMessageRepository(
                    messagesBySession =
                        mutableMapOf(
                            "ses-A" to
                                listOf(
                                    Message(id = "msg-a", role = MessageRole.Assistant, text = "session-A"),
                                ),
                            "ses-B" to
                                listOf(
                                    Message(id = "msg-b", role = MessageRole.Assistant, text = "session-B"),
                                ),
                        ),
                )
            val eventStream = FakeForegroundEventStream()
            val sync = createMessageSync(repository, eventStream, pollIntervalMs = 60_000L)

            val emissionsA1 = mutableListOf<List<Message>>()
            val jobA1 =
                launch {
                    sync.observeMessages("ses-A", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissionsA1.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            assertEquals("session-A", emissionsA1.last().single().text)
            assertTrue(emissionsA1.none { frame -> frame.any { it.id == "msg-b" } })
            jobA1.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val emissionsB = mutableListOf<List<Message>>()
            val jobB =
                launch {
                    sync.observeMessages("ses-B", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissionsB.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            assertEquals("session-B", emissionsB.last().single().text)
            assertTrue(emissionsB.none { frame -> frame.any { it.id == "msg-a" } })

            // Foreign session events while observing B must not leak A content.
            eventStream.emit(
                OpencodeEvent(
                    type = "message.updated",
                    sessionId = "ses-A",
                    rawProperties =
                        buildJsonObject {
                            put(
                                "info",
                                buildJsonObject {
                                    put("id", "msg-a-foreign")
                                    put("role", "assistant")
                                    put("sessionID", "ses-A")
                                },
                            )
                        },
                ),
            )
            advanceTimeBy(50)
            assertTrue(emissionsB.none { frame -> frame.any { it.id == "msg-a" || it.id == "msg-a-foreign" } })
            assertEquals("session-B", emissionsB.last().single().text)

            jobB.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val emissionsA2 = mutableListOf<List<Message>>()
            val jobA2 =
                launch {
                    sync.observeMessages("ses-A", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissionsA2.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            assertEquals("session-A", emissionsA2.last().single().text)
            assertTrue(emissionsA2.none { frame -> frame.any { it.id == "msg-b" } })
            jobA2.cancel()
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
                    pollIntervalMs = 60_000L,
                    eventStagingCapacity = 64,
                    eventStagingByteCapacity = 200,
                    recoveryDelay = { delay(1) },
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
            assertTrue(sync.syncHealth("ses-1", "/repo") is MessageSyncHealth.Recovering)
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
            advanceTimeBy(80)
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
            assertEquals(
                MessageSyncRecoveryReason.TransportUnavailable,
                (health as MessageSyncHealth.Stale).reason,
            )
            assertEquals("Hello", emissions.last().single().text)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_backgroundResumeToLiveWithoutReconnect_refreshesAfterStreamingLatch() =
        runTest(testDispatcher) {
            // Repro for grocery-shopping resume: mid-stream background stop clears
            // wasConnected so cold start reaches Connected/Live without
            // stream.reconnected. If server.connected is also missed, the open
            // streaming latch must not block poll/recovery forever — otherwise UI
            // stays Live on a stale timeline until pull-to-refresh.
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val epoch = ConnectionEpoch()
            val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
            val sync =
                MessageSync(
                    listMessages = { sessionId, directory -> repository.listMessages(sessionId, directory) },
                    engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                    eventStream = eventStream,
                    liveStoreScope = scope,
                    pollIntervalMs = LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS,
                    connectionEpoch = epoch,
                    recoveryDelay = { delay(1) },
                )
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)

            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            advanceTimeBy(1)
            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Partial"))
            advanceTimeBy(50)
            assertEquals("Partial", emissions.last().first { it.id == "msg-2" }.text)

            // Background: ProcessLifecycle stop grace ends → SSE Idle.
            eventStream.setConnectionState(EventStreamConnectionState.Idle)
            advanceTimeBy(1)
            assertTrue(sync.syncHealth("ses-1", "/repo") is MessageSyncHealth.Stale)

            // Server finished the turn while backgrounded.
            repository.messages =
                listOf(
                    Message(id = "msg-1", role = MessageRole.User, text = "Hello"),
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "Finished while away",
                        parts = listOf(MessagePart.Text(text = "Finished while away", isStreaming = false)),
                    ),
                )

            // Foreground resume: start() bumps epoch and HTTP open marks Connected
            // (Live) without emitting stream.reconnected after stop() cleared wasConnected.
            epoch.bump()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            advanceTimeBy(LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS + 50)

            assertEquals(
                "Finished while away",
                emissions.last().first { it.id == "msg-2" }.text,
            )
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_resumeCatchUp_startsOnConnectingAndCoalescesServerConnected() =
        runTest(testDispatcher) {
            // OPE-165 snapshot: cached messages stay, one list fetch before live,
            // TransportResumed + server.connected share one catch-up.
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val epoch = ConnectionEpoch()
            val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
            val sync =
                MessageSync(
                    listMessages = { sessionId, directory -> repository.listMessages(sessionId, directory) },
                    engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                    eventStream = eventStream,
                    liveStoreScope = scope,
                    pollIntervalMs = 60_000L,
                    connectionEpoch = epoch,
                    recoveryDelay = { delay(1) },
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
            val callsAfterInitial = repository.listMessagesCallCount

            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            advanceTimeBy(1)
            eventStream.setConnectionState(EventStreamConnectionState.Idle)
            advanceTimeBy(1)
            assertTrue(sync.syncHealth("ses-1", "/repo") is MessageSyncHealth.Stale)
            assertEquals("Hello", emissions.last().single().text)

            repository.messages =
                listOf(
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "Caught up while connecting",
                    ),
                )
            epoch.bump()
            eventStream.setConnectionState(EventStreamConnectionState.Connecting)
            advanceTimeBy(50)
            assertEquals("Caught up while connecting", emissions.last().single().text)
            assertEquals(callsAfterInitial + 1, repository.listMessagesCallCount)

            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(LiveStoreRefreshPolicy.MESSAGE_DEBOUNCE_MS + 50)

            assertEquals(callsAfterInitial + 1, repository.listMessagesCallCount)
            assertEquals("Caught up while connecting", emissions.last().single().text)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_backgroundResumeToLiveWithoutReconnect_refreshesWhenNotStreaming() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val epoch = ConnectionEpoch()
            val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
            val sync =
                MessageSync(
                    listMessages = { sessionId, directory -> repository.listMessages(sessionId, directory) },
                    engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                    eventStream = eventStream,
                    liveStoreScope = scope,
                    pollIntervalMs = LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS,
                    connectionEpoch = epoch,
                    recoveryDelay = { delay(1) },
                )
            val emissions = mutableListOf<List<Message>>()
            val collectJob =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { update ->
                        if (update is MessageListUpdate.Messages) emissions.add(update.messages)
                    }
                }
            advanceTimeBy(1)
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            advanceTimeBy(1)

            eventStream.setConnectionState(EventStreamConnectionState.Idle)
            advanceTimeBy(1)
            repository.messages =
                listOf(Message(id = "msg-2", role = MessageRole.Assistant, text = "Updated while away"))

            epoch.bump()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            advanceTimeBy(LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS + 50)

            assertEquals("Updated while away", emissions.last().single().text)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_staleEpochEventDoesNotMutateStore() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val epoch = ConnectionEpoch()
            val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
            val sync =
                MessageSync(
                    listMessages = { sessionId, directory -> repository.listMessages(sessionId, directory) },
                    engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                    eventStream = eventStream,
                    liveStoreScope = scope,
                    pollIntervalMs = 60_000L,
                    connectionEpoch = epoch,
                    recoveryDelay = { delay(1) },
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
            val before = emissions.size

            epoch.bump()
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-1", delta = " should-not-apply"))
            advanceTimeBy(50)

            assertEquals(before, emissions.size)
            assertEquals("Hello", emissions.last().single().text)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_serverConnectedAfterEpochBumpRecoversAndAppliesLiveDeltas() =
        runTest(testDispatcher) {
            val repository = FakeMessageRepository()
            val eventStream = FakeForegroundEventStream()
            val epoch = ConnectionEpoch()
            val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
            val sync =
                MessageSync(
                    listMessages = { sessionId, directory -> repository.listMessages(sessionId, directory) },
                    engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                    eventStream = eventStream,
                    liveStoreScope = scope,
                    pollIntervalMs = 60_000L,
                    connectionEpoch = epoch,
                    recoveryDelay = { delay(1) },
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

            // Foreground SSE start bumps the shared epoch, then emits server.connected.
            // Recovery must adopt the new generation or it latches Recovering forever.
            epoch.bump()
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(LiveStoreRefreshPolicy.MESSAGE_DEBOUNCE_MS + 50)

            assertEquals(MessageSyncHealth.Healthy, sync.syncHealth("ses-1", "/repo"))

            eventStream.emit(messageUpdatedEvent("msg-2", "assistant"))
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Live after reconnect"))
            advanceTimeBy(50)

            assertEquals(
                "Live after reconnect",
                emissions.last().first { it.id == "msg-2" }.text,
            )
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)
        }

    @Test
    fun observeMessages_fullReloadOnReconnected() =
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
    fun observeMessages_pollsTimelineWhenFingerprintChanges() =
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
            advanceTimeBy(LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS + 1)

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
            advanceTimeBy(LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS)

            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Live"))
            advanceTimeBy(1)
            pollGate.complete(Unit)
            advanceTimeBy(1)

            assertEquals("Live", emissions.last().first { it.id == "msg-2" }.text)
            collectJob.cancel()
        }

    @Test
    fun observeMessages_messagePartDelta_ignoresReasoningPartDeltas() =
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
            eventStream.emit(partDeltaEvent(partId = "prt-reason", messageId = "msg-2", delta = "The user said hello."))
            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = ""))
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Hello!"))
            advanceTimeBy(350)
            collectJob.cancel()
            advanceTimeBy(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS)

            val msg2 = emissions.last().first { it.id == "msg-2" }
            assertEquals("Hello!", msg2.text)
            assertEquals(1, msg2.parts.size)
            assertTrue(msg2.parts.single() is MessagePart.Text)
        }

    @Test
    fun observeMessages_sessionIdleReloadsAndClearsStreaming() =
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

            val final = emissions.last().single()
            assertEquals("Hello", final.text)
            assertFalse((final.parts.single() as MessagePart.Text).isStreaming)
            assertEquals(2, repository.listMessagesCallCount)
        }

    @Test
    fun observeMessages_failedIdleReloadEndsStreamingAndPollingRecovers() =
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
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "Partial"))
            advanceTimeBy(1)

            repository.shouldFailLoad = true
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            advanceTimeBy(1)
            val recoveredPart =
                emissions
                    .last()
                    .first { it.id == "msg-2" }
                    .parts
                    .first() as MessagePart.Text
            assertFalse(recoveredPart.isStreaming)

            repository.shouldFailLoad = false
            repository.messages =
                listOf(
                    Message(
                        id = "msg-2",
                        role = MessageRole.Assistant,
                        text = "Final",
                        parts = listOf(MessagePart.Text("Final")),
                    ),
                )
            advanceTimeBy(LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS + 1)
            assertEquals("Final", emissions.last().single().text)
            collectJob.cancel()
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
            advanceTimeBy(50)
            assertTrue(emissions.last().isStreaming)
            assertEquals("Hello", emissions.last().text)

            eventStream.emit(textPartEvent(partId = "prt-1", messageId = "msg-2", text = "Hello"))
            advanceTimeBy(50)
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
            eventStream.emit(partDeltaEvent(partId = "prt-1", messageId = "msg-2", delta = "!", field = "reasoning"))
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
        pollIntervalMs: Long = LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS,
    ): MessageSync {
        val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        return MessageSync(
            listMessages = { sessionId, directory -> repository.listMessages(sessionId, directory) },
            engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
            eventStream = eventStream,
            liveStoreScope = scope,
            pollIntervalMs = pollIntervalMs,
            eventStagingCapacity = eventStagingCapacity,
            connectionProfileIdProvider = { connectionProfileId },
            recoveryDelay = { delay(1) },
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
    fun observeStatuses_keepsPriorWhenStatusLoadFails() =
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
            assertEquals("busy", emissions.last()["ses-1"]?.raw?.get("type"))

            repository.shouldFailStatuses = true
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(350)
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(1, emissions.size)
            assertEquals("busy", emissions.last()["ses-1"]?.raw?.get("type"))
        }

    @Test
    fun observeStatuses_appliesStatusEventLocallyWithoutWaitingForGet() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<Map<String, SessionStatus>>()
            val collectJob =
                launch {
                    store.observeStatuses("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()
            val fetchesAfterBootstrap = repository.statusFetchCount

            eventStream.emit(
                OpencodeEvent(
                    type = "session.status",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put("sessionID", "ses-1")
                            putJsonObject("status") { put("type", "busy") }
                        },
                ),
            )
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals("busy", emissions.last()["ses-1"]?.raw?.get("type"))
            assertEquals(fetchesAfterBootstrap, repository.statusFetchCount)
        }

    @Test
    fun observeStatuses_emptyActiveSnapshotDoesNotClearSseBusy() =
        runTest(testDispatcher) {
            val repository = FakeSessionRepository(statuses = emptyList())
            val eventStream = FakeForegroundEventStream()
            val store = createStatusStore(repository, eventStream)
            val emissions = mutableListOf<Map<String, SessionStatus>>()
            val collectJob =
                launch {
                    store.observeStatuses("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            eventStream.emit(
                OpencodeEvent(
                    type = "session.status",
                    sessionId = "ses-1",
                    rawProperties =
                        buildJsonObject {
                            put("sessionID", "ses-1")
                            putJsonObject("status") { put("type", "busy") }
                        },
                ),
            )
            advanceUntilIdle()
            assertEquals("busy", emissions.last()["ses-1"]?.raw?.get("type"))

            eventStream.emit(OpencodeEvent(type = "session.active.snapshot", sessionId = "ses-1"))
            advanceTimeBy(350)
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals("busy", emissions.last()["ses-1"]?.raw?.get("type"))
        }

    @Test
    fun observeStatuses_removesSessionOnIdleEvent() =
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

            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(2, emissions.size)
            assertTrue(emissions.last().isEmpty())
        }

    @Test
    fun observeStatuses_refreshesOnServerConnectedAfterBackgroundResume() =
        runTest(testDispatcher) {
            // Background stop() clears wasConnected, so cold foreground start() does not
            // emit stream.reconnected — only server.connected. Status must still reload
            // so a session that finished while backgrounded does not stay "busy".
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
            assertEquals("busy", emissions.last()["ses-1"]?.raw?.get("type"))

            repository.statuses = emptyList()
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceTimeBy(350)
            advanceUntilIdle()
            collectJob.cancel()

            assertEquals(2, emissions.size)
            assertEquals(emptyMap<String, SessionStatus>(), emissions.last())
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
                    sessionCache = NoOpSessionCache,
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
        holdLoadUntil?.await()
        nextLoadGate?.also {
            nextLoadGate = null
            it.await()
        }
        listMessagesCallCount += 1
        if (shouldFailMissingSession) error("NotFoundError Session not found: $sessionId")
        if (shouldFailLoad) error("load failed")
        return messagesBySession[sessionId] ?: messages
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
    var todos: List<SessionTodo> = emptyList(),
    var listTodosCallCount: Int = 0,
    var statusFetchCount: Int = 0,
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
        statusFetchCount += 1
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
