package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.sync.testSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageSyncWindowedHistoryTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun initialSubscribe_usesTailAndPreservesOlderCacheRows() =
        runTest(testDispatcher) {
            val cache =
                SeededSessionCache(
                    listOf(
                        user("old"),
                        user("mid"),
                        user("new"),
                    ),
                )
            val tailCalls = mutableListOf<String>()
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        tailCalls += "tail"
                        MessagePage(
                            messages = listOf(user("mid"), user("new")),
                            olderCursor = "older",
                            hasOlder = true,
                        )
                    },
                    sessionCache = cache,
                )

            val updates = mutableListOf<MessageListUpdate>()
            sync
                .observeMessages("ses-1", "/repo")
                .onEach { updates += it }
                .first { update ->
                    update is MessageListUpdate.CacheSyncConfirmed ||
                        (update is MessageListUpdate.Messages && !update.isFromCache)
                }

            val lastMessages = updates.filterIsInstance<MessageListUpdate.Messages>().last()
            assertEquals(listOf("old", "mid", "new"), lastMessages.messages.map { it.id })
            assertEquals(listOf("tail"), tailCalls)
            val confirmed = updates.filterIsInstance<MessageListUpdate.CacheSyncConfirmed>().lastOrNull()
            assertTrue(lastMessages.hasOlder || confirmed?.hasOlder == true)
            assertEquals(1, updates.filterIsInstance<MessageListUpdate.Messages>().size)
        }

    @Test
    fun noOverlapTail_resetsInsteadOfConcatenating() =
        runTest(testDispatcher) {
            val cache = SeededSessionCache(listOf(user("a"), user("b")))
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("x"), user("y")),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                    sessionCache = cache,
                )

            val last =
                sync
                    .observeMessages("ses-1", "/repo")
                    .first { update ->
                        update is MessageListUpdate.Messages && !update.isFromCache
                    } as MessageListUpdate.Messages

            assertEquals(listOf("x", "y"), last.messages.map { it.id })
        }

    @Test
    fun identicalTail_confirmsWithoutRepublish() =
        runTest(testDispatcher) {
            val cache = SeededSessionCache(listOf(user("m1"), user("m2")))
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("m1"), user("m2")),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                    sessionCache = cache,
                )
            val updates = mutableListOf<MessageListUpdate>()
            val job =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { updates += it }
                }
            advanceUntilIdle()
            job.cancel()
            assertEquals(1, updates.filterIsInstance<MessageListUpdate.Messages>().size)
            assertTrue(updates.any { it is MessageListUpdate.CacheSyncConfirmed })
        }

    @Test
    fun identicalTail_resubscribeWithinTimeout_replaysMessagesNotOnlyCacheSyncConfirmed() =
        runTest(testDispatcher) {
            val cache = SeededSessionCache(listOf(user("m1"), user("m2")))
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("m1"), user("m2")),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                    sessionCache = cache,
                )
            val first =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { }
                }
            advanceUntilIdle()
            first.cancel()
            // Stay inside WhileSubscribed timeout so the shared flow does not restart.
            advanceTimeBy(1_000)

            val replayed = mutableListOf<MessageListUpdate>()
            val second =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { replayed += it }
                }
            advanceUntilIdle()
            second.cancel()

            val messages = replayed.filterIsInstance<MessageListUpdate.Messages>()
            assertTrue(
                "warm reopen must replay the cached transcript, not only CacheSyncConfirmed. " +
                    "got=${replayed.map { it::class.simpleName }}",
                messages.isNotEmpty(),
            )
            assertEquals(listOf("m1", "m2"), messages.first().messages.map { it.id })
        }

    @Test
    fun loadOlder_thenResubscribeWithinTimeout_keepsExpandedSnapshotNotStaleReplay() =
        runTest(testDispatcher) {
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("c"), user("d")),
                            olderCursor = "page-older",
                            hasOlder = true,
                        )
                    },
                    listOlderMessages = { _, _, _ ->
                        MessagePage(
                            messages = listOf(user("a"), user("b")),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                )
            val first =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { }
                }
            advanceUntilIdle()
            sync.loadOlderMessages("ses-1", "/repo")
            first.cancel()
            // Stay inside WhileSubscribed timeout so the shared flow does not restart.
            advanceTimeBy(1_000)

            val replayed = mutableListOf<MessageListUpdate>()
            val second =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { replayed += it }
                }
            advanceUntilIdle()
            second.cancel()

            val messages = replayed.filterIsInstance<MessageListUpdate.Messages>()
            assertTrue(
                "warm reopen after loadOlder must paint the expanded transcript. " +
                    "got=${replayed.map { it::class.simpleName }}",
                messages.isNotEmpty(),
            )
            assertEquals(listOf("a", "b", "c", "d"), messages.first().messages.map { it.id })
            assertFalse(messages.first().hasOlder)
            // Shared replay must not regress to the pre-loadOlder tail or stale hasOlder.
            assertTrue(
                messages.none { it.messages.map { message -> message.id } == listOf("c", "d") },
            )
            assertTrue(
                replayed
                    .filterIsInstance<MessageListUpdate.CacheSyncConfirmed>()
                    .none { it.hasOlder },
            )
        }

    @Test
    fun differingTail_mergesAndPublishesOnceAfterCache() =
        runTest(testDispatcher) {
            val cache = SeededSessionCache(listOf(user("m1")))
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("m1"), user("m2")),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                    sessionCache = cache,
                )
            val updates = mutableListOf<MessageListUpdate>()
            val job =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { updates += it }
                }
            advanceUntilIdle()
            job.cancel()
            val published = updates.filterIsInstance<MessageListUpdate.Messages>()
            assertEquals(2, published.size)
            assertTrue(published.first().isFromCache)
            assertFalse(published.last().isFromCache)
            assertEquals(listOf("m1", "m2"), published.last().messages.map { it.id })
        }

    @Test
    fun cachedRevertedTurn_restTailWithoutIt_dropsTheRevertedRows() =
        runTest(testDispatcher) {
            // Cold start: the cache still holds a turn the server reverted and removed.
            val cache =
                SeededSessionCache(
                    listOf(
                        user("u1"),
                        assistant("a1"),
                        Message(id = "u2", role = MessageRole.User, text = "Queued prompt"),
                        Message(id = "a2", role = MessageRole.Assistant, text = "A long reply to the reverted prompt."),
                        user("u3"),
                        assistant("a3"),
                    ),
                )
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("u1"), assistant("a1"), user("u3"), assistant("a3")),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                    sessionCache = cache,
                )
            val updates = mutableListOf<MessageListUpdate>()
            val job =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { updates += it }
                }
            advanceUntilIdle()
            job.cancel()
            val published = updates.filterIsInstance<MessageListUpdate.Messages>()
            assertFalse(published.last().isFromCache)
            assertEquals(listOf("u1", "a1", "u3", "a3"), published.last().messages.map { it.id })
        }

    @Test
    fun streamingCachedTail_completedRestTail_publishesRestContent() =
        runTest(testDispatcher) {
            val cache =
                SeededSessionCache(
                    listOf(
                        user("m1"),
                        Message(
                            id = "a1",
                            role = MessageRole.Assistant,
                            text = "partial",
                            parts = listOf(MessagePart.Text(text = "partial", isStreaming = true)),
                            sentAtEpochMs = 1_000L,
                            completedAtEpochMs = null,
                        ),
                    ),
                )
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages =
                                listOf(
                                    user("m1"),
                                    Message(
                                        id = "a1",
                                        role = MessageRole.Assistant,
                                        text = "partial and done",
                                        parts = listOf(MessagePart.Text(text = "partial and done")),
                                        sentAtEpochMs = 1_000L,
                                        completedAtEpochMs = 2_000L,
                                    ),
                                ),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                    sessionCache = cache,
                )
            val updates = mutableListOf<MessageListUpdate>()
            val job =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { updates += it }
                }
            advanceUntilIdle()
            job.cancel()
            val published = updates.filterIsInstance<MessageListUpdate.Messages>()
            assertEquals(2, published.size)
            assertTrue(published.first().isFromCache)
            assertFalse(published.last().isFromCache)
            assertEquals(
                "partial and done",
                published
                    .last()
                    .messages
                    .last()
                    .text,
            )
        }

    @Test
    fun completedIdenticalAssistantTail_confirmsWithoutRepublish() =
        runTest(testDispatcher) {
            val completed =
                Message(
                    id = "a1",
                    role = MessageRole.Assistant,
                    text = "done",
                    parts = listOf(MessagePart.Text(text = "done")),
                    sentAtEpochMs = 1_000L,
                    completedAtEpochMs = 2_000L,
                )
            val cache = SeededSessionCache(listOf(user("m1"), completed))
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("m1"), completed),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                    sessionCache = cache,
                )
            val updates = mutableListOf<MessageListUpdate>()
            val job =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { updates += it }
                }
            advanceUntilIdle()
            job.cancel()
            assertEquals(1, updates.filterIsInstance<MessageListUpdate.Messages>().size)
            assertTrue(updates.any { it is MessageListUpdate.CacheSyncConfirmed })
        }

    @Test
    fun restTailStillStreaming_doesNotConfirm() =
        runTest(testDispatcher) {
            val streaming =
                Message(
                    id = "a1",
                    role = MessageRole.Assistant,
                    text = "partial",
                    parts = listOf(MessagePart.Text(text = "partial", isStreaming = true)),
                    sentAtEpochMs = 1_000L,
                    completedAtEpochMs = null,
                )
            val cache = SeededSessionCache(listOf(user("m1"), streaming))
            val restTail =
                Message(
                    id = "a1",
                    role = MessageRole.Assistant,
                    text = "still going",
                    parts = listOf(MessagePart.Text(text = "still going", isStreaming = true)),
                    sentAtEpochMs = 1_000L,
                    completedAtEpochMs = null,
                )
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("m1"), restTail),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                    sessionCache = cache,
                )
            val updates = mutableListOf<MessageListUpdate>()
            val job =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { updates += it }
                }
            advanceUntilIdle()
            job.cancel()
            val published = updates.filterIsInstance<MessageListUpdate.Messages>()
            assertEquals(2, published.size)
            assertFalse(updates.any { it is MessageListUpdate.CacheSyncConfirmed })
        }

    @Test
    fun initialSubscribe_publishesTailBeforeSplitTurnBackfill() =
        runTest(testDispatcher) {
            val olderCalls = mutableListOf<String>()
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(assistant("a20")),
                            olderCursor = "c1",
                            hasOlder = true,
                        )
                    },
                    listOlderMessages = { _, _, cursor ->
                        olderCalls += cursor
                        MessagePage(
                            messages = listOf(user("u1"), assistant("a20")),
                            olderCursor = "c0",
                            hasOlder = true,
                        )
                    },
                )

            val updates = mutableListOf<MessageListUpdate>()
            val job =
                launch {
                    sync.observeMessages("ses-1", "/repo").collect { updates += it }
                }
            advanceUntilIdle()
            job.cancel()

            val published =
                updates.filterIsInstance<MessageListUpdate.Messages>().map { it.messages.map { message -> message.id } }
            assertEquals(listOf("a20"), published.first { it.isNotEmpty() })
            assertEquals(listOf("u1", "a20"), published.last { it.isNotEmpty() })
            assertEquals(listOf("c1"), olderCalls)
        }

    @Test
    fun refresh_backfillsSplitTurnAfterTail() =
        runTest(testDispatcher) {
            val olderCalls = mutableListOf<String>()
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(assistant("a20")),
                            olderCursor = "c1",
                            hasOlder = true,
                        )
                    },
                    listOlderMessages = { _, _, cursor ->
                        olderCalls += cursor
                        MessagePage(
                            messages = listOf(user("u1"), assistant("a20")),
                            olderCursor = "c0",
                            hasOlder = true,
                        )
                    },
                )

            val job = launch { sync.observeMessages("ses-1", "/repo").collect { } }
            advanceUntilIdle()
            val refreshed = sync.refreshMessages("ses-1", "/repo")
            job.cancel()

            assertEquals(listOf("u1", "a20"), refreshed.map { it.id })
            assertEquals(listOf("c1"), olderCalls)
        }

    @Test
    fun loadOlder_prependsAndStopsAtHistoryEnd() =
        runTest(testDispatcher) {
            val olderCalls = mutableListOf<String>()
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("c"), user("d")),
                            olderCursor = "page-older",
                            hasOlder = true,
                        )
                    },
                    listOlderMessages = { _, _, cursor ->
                        olderCalls += cursor
                        MessagePage(
                            messages = listOf(user("a"), user("b")),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                )

            sync.observeMessages("ses-1", "/repo").first()
            advanceUntilIdle()
            val result = sync.loadOlderMessages("ses-1", "/repo")

            assertEquals(listOf("page-older"), olderCalls)
            assertEquals(listOf("a", "b", "c", "d"), result.messages.map { it.id })
            assertFalse(result.hasOlder)
            assertEquals(2, result.addedCount)
        }

    @Test
    fun loadOlder_oneRequestEvenWhenPageFullyOverlapsCache() =
        runTest(testDispatcher) {
            val olderCalls = mutableListOf<String>()
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("c"), user("d")),
                            olderCursor = "page-1",
                            hasOlder = true,
                        )
                    },
                    listOlderMessages = { _, _, cursor ->
                        olderCalls += cursor
                        when (cursor) {
                            "page-1" ->
                                MessagePage(
                                    messages = listOf(user("a"), user("b")),
                                    olderCursor = "page-2",
                                    hasOlder = true,
                                )
                            else ->
                                MessagePage(
                                    messages = listOf(user("pre")),
                                    olderCursor = null,
                                    hasOlder = false,
                                )
                        }
                    },
                    sessionCache =
                        SeededSessionCache(
                            listOf(user("a"), user("b"), user("c"), user("d")),
                        ),
                )

            sync.observeMessages("ses-1", "/repo").first()
            advanceUntilIdle()
            val first = sync.loadOlderMessages("ses-1", "/repo")

            assertEquals(listOf("page-1"), olderCalls)
            assertEquals(0, first.addedCount)
            assertTrue(first.hasOlder)
            assertEquals(listOf("a", "b", "c", "d"), first.messages.map { it.id })

            val second = sync.loadOlderMessages("ses-1", "/repo")
            assertEquals(listOf("page-1", "page-2"), olderCalls)
            assertEquals(1, second.addedCount)
            assertEquals(listOf("pre", "a", "b", "c", "d"), second.messages.map { it.id })
            assertFalse(second.hasOlder)
        }

    @Test
    fun loadOlder_preservesSseRevisionRace() =
        runTest(testDispatcher) {
            val engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter()))
            val storeSlot = MessageSessionKey.of("local", "/repo", "ses-1").storeSlot
            val sync =
                MessageSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages =
                                listOf(
                                    Message(
                                        id = "m3",
                                        role = MessageRole.Assistant,
                                        text = "Hello",
                                        parts = listOf(MessagePart.Text(text = "Hello", isStreaming = true, id = "prt-1")),
                                    ),
                                ),
                            olderCursor = "c1",
                            hasOlder = true,
                        )
                    },
                    listOlderMessages = { _, _, _ ->
                        engine.apply(
                            storeSlot,
                            MessagePatch.PartDelta(messageId = "m3", partId = "prt-1", delta = " world"),
                        )
                        MessagePage(
                            messages =
                                listOf(
                                    user("m1"),
                                    Message(id = "m3", role = MessageRole.Assistant, text = "Stale"),
                                ),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                    engine = engine,
                    eventStream = FakeForegroundEventStream(),
                    liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                    syncCoordinator =
                        testSyncCoordinator(
                            eventStream = FakeForegroundEventStream(),
                            scope = CoroutineScope(SupervisorJob() + testDispatcher),
                            nowMs = { testDispatcher.scheduler.currentTime },
                            wait = { kotlinx.coroutines.delay(it) },
                        ),
                    nowMs = { testDispatcher.scheduler.currentTime },
                )

            sync.observeMessages("ses-1", "/repo").first()
            advanceUntilIdle()
            val result = sync.loadOlderMessages("ses-1", "/repo")

            assertEquals(listOf("m1", "m3"), result.messages.map { it.id })
            assertEquals("Hello world", result.messages.last().text)
        }

    @Test
    fun loadOlder_doesNotExtraPublishThroughObservePath() =
        runTest(testDispatcher) {
            val updates = mutableListOf<MessageListUpdate>()
            val sync =
                createSync(
                    listMessageTail = { _, _ ->
                        MessagePage(
                            messages = listOf(user("c"), user("d")),
                            olderCursor = "page-older",
                            hasOlder = true,
                        )
                    },
                    listOlderMessages = { _, _, _ ->
                        MessagePage(
                            messages = listOf(user("a"), user("b")),
                            olderCursor = null,
                            hasOlder = false,
                        )
                    },
                )

            backgroundScope.launch {
                sync.observeMessages("ses-1", "/repo").collect { updates += it }
            }
            advanceUntilIdle()
            val publishedBefore = updates.count { it is MessageListUpdate.Messages }
            sync.loadOlderMessages("ses-1", "/repo")
            advanceUntilIdle()

            assertEquals(publishedBefore, updates.count { it is MessageListUpdate.Messages })
        }

    @Test
    fun noOverlapTail_replacesOlderCursor() =
        runTest(testDispatcher) {
            val olderCalls = mutableListOf<String>()
            var tailPage =
                MessagePage(
                    messages = listOf(user("m3")),
                    olderCursor = "c-tail",
                    hasOlder = true,
                )
            val sync =
                createSync(
                    listMessageTail = { _, _ -> tailPage },
                    listOlderMessages = { _, _, cursor ->
                        olderCalls += cursor
                        when (cursor) {
                            "c-tail" ->
                                MessagePage(
                                    messages = listOf(user("m1"), user("m3")),
                                    olderCursor = "c-older",
                                    hasOlder = true,
                                )
                            "c-reset" ->
                                MessagePage(
                                    messages = listOf(user("n0")),
                                    olderCursor = null,
                                    hasOlder = false,
                                )
                            else ->
                                MessagePage(
                                    messages = listOf(user("stale")),
                                    olderCursor = null,
                                    hasOlder = false,
                                )
                        }
                    },
                )

            val job = launch { sync.observeMessages("ses-1", "/repo").collect { } }
            advanceUntilIdle()
            sync.loadOlderMessages("ses-1", "/repo")
            assertEquals(listOf("c-tail"), olderCalls)

            tailPage =
                MessagePage(
                    messages = listOf(user("n1"), user("n2")),
                    olderCursor = "c-reset",
                    hasOlder = true,
                )
            sync.refreshMessages("ses-1", "/repo")
            val result = sync.loadOlderMessages("ses-1", "/repo")
            job.cancel()

            assertEquals(listOf("c-tail", "c-reset"), olderCalls)
            assertEquals(listOf("n0", "n1", "n2"), result.messages.map { it.id })
            assertFalse(result.hasOlder)
            assertFalse(result.messages.any { it.id == "stale" || it.id == "m1" })
        }

    @Test
    fun refresh_usesTailFetcherNotFullList() =
        runTest(testDispatcher) {
            var fullCalls = 0
            var tailCalls = 0
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(dev.opencode.mobile.domain.event.EventStreamConnectionState.Connected)
            val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
            val sync =
                MessageSync(
                    listMessages = { _, _ ->
                        fullCalls += 1
                        listOf(user("full"))
                    },
                    listMessageTail = { _, _ ->
                        tailCalls += 1
                        MessagePage(listOf(user("tail")), olderCursor = null, hasOlder = false)
                    },
                    engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                    eventStream = eventStream,
                    liveStoreScope = scope,
                    syncCoordinator =
                        testSyncCoordinator(
                            eventStream = eventStream,
                            scope = scope.coroutineScope,
                            nowMs = { testDispatcher.scheduler.currentTime },
                            wait = { kotlinx.coroutines.delay(1) },
                        ),
                    nowMs = { testDispatcher.scheduler.currentTime },
                )

            val collectJob = launch { sync.observeMessages("ses-1", "/repo").collect { } }
            advanceUntilIdle()
            sync.refreshMessages("ses-1", "/repo")
            collectJob.cancel()

            assertEquals(0, fullCalls)
            assertTrue(tailCalls >= 2)
        }

    @Test
    fun cachedTailWithoutToolImages_publishesServerImages() =
        runTest(testDispatcher) {
            val cached = toolAssistant(dataUrl = "")
            val fetched = toolAssistant(dataUrl = PNG_URL)
            val sync =
                createSync(
                    listMessageTail = { _, _ -> MessagePage(listOf(user("u1"), fetched), olderCursor = null, hasOlder = false) },
                    sessionCache = SeededSessionCache(listOf(user("u1"), cached)),
                )
            val updates = mutableListOf<MessageListUpdate>()
            val job = launch { sync.observeMessages("ses-1", "/repo").collect { updates += it } }
            advanceUntilIdle()
            job.cancel()

            val live = updates.filterIsInstance<MessageListUpdate.Messages>().last { !it.isFromCache }
            val lastMessage = live.messages.last()
            val tool = lastMessage.parts.single() as MessagePart.Tool
            val image = tool.images.single()
            assertEquals(PNG_URL, image.dataUrl)
        }

    @Test
    fun matchingCachedTail_hydratesBlankedFileDataAndPublishes() =
        runTest(testDispatcher) {
            val sync =
                createSync(
                    listMessageTail = { _, _ -> MessagePage(listOf(userWithFile(PNG_URL)), olderCursor = null, hasOlder = false) },
                    sessionCache = SeededSessionCache(listOf(userWithFile(""))),
                )
            val updates = mutableListOf<MessageListUpdate>()
            val job = launch { sync.observeMessages("ses-1", "/repo").collect { updates += it } }
            advanceUntilIdle()
            job.cancel()

            val messages = updates.filterIsInstance<MessageListUpdate.Messages>()
            assertEquals(2, messages.size)
            assertTrue(messages.first().isFromCache)
            val lastFrame = messages.last().messages
            val file = lastFrame.single().parts.single() as MessagePart.File
            assertFalse(messages.last().isFromCache)
            assertEquals(PNG_URL, file.dataUrl)
        }

    @Test
    fun fullImage_returnsStoredDataWithoutFetching() =
        runTest(testDispatcher) {
            var fetches = 0
            val sync =
                createSync(
                    listMessageTail = { _, _ -> MessagePage(listOf(toolAssistant(PNG_URL)), olderCursor = null, hasOlder = false) },
                    getMessage = { _, _, _ ->
                        fetches += 1
                        toolAssistant(PNG_URL)
                    },
                )
            val job = launch { sync.observeMessages("ses-1", "/repo").collect { } }
            advanceUntilIdle()

            val image = sync.fullImage("ses-1", "/repo", "a1", ToolResultImages.id("prt-tool", 0))
            job.cancel()

            assertEquals(PNG_URL, image.dataUrl)
            assertEquals(0, fetches)
        }

    @Test
    fun fullImage_fetchesTheMessageAndHydratesTheStore() =
        runTest(testDispatcher) {
            val fetched = mutableListOf<Triple<String, String?, String>>()
            // The cached copy and the server copy carry the same tail identity only when the
            // server copy has no full image either; the viewer fetch then fills it in.
            val sync =
                createSync(
                    listMessageTail = { _, _ -> MessagePage(listOf(toolAssistant("")), olderCursor = null, hasOlder = false) },
                    sessionCache = SeededSessionCache(listOf(toolAssistant(""))),
                    getMessage = { sessionId, directory, messageId ->
                        fetched += Triple(sessionId, directory, messageId)
                        toolAssistant(PNG_URL)
                    },
                )
            val updates = mutableListOf<MessageListUpdate>()
            val job = launch { sync.observeMessages("ses-1", "/repo").collect { updates += it } }
            advanceUntilIdle()

            val image = sync.fullImage("ses-1", "/repo", "a1", ToolResultImages.id("prt-tool", 0))
            advanceUntilIdle()
            job.cancel()

            assertEquals(PNG_URL, image.dataUrl)
            assertEquals(listOf(Triple("ses-1", "/repo", "a1")), fetched)
            val published = updates.filterIsInstance<MessageListUpdate.Messages>().last()
            val publishedMessage = published.messages.single()
            val publishedTool = publishedMessage.parts.single() as MessagePart.Tool
            assertEquals(PNG_URL, publishedTool.images.single().dataUrl)
            // A second open is a store hit.
            sync.fullImage("ses-1", "/repo", "a1", ToolResultImages.id("prt-tool", 0))
            assertEquals(1, fetched.size)
        }

    @Test
    fun fullImage_throwsWhenTheFetchFailsOrLacksTheImage() =
        runTest(testDispatcher) {
            val failing =
                createSync(
                    listMessageTail = { _, _ -> MessagePage(emptyList(), olderCursor = null, hasOlder = false) },
                    getMessage = { _, _, _ -> throw MessageError.Http(statusCode = 404, message = "not found") },
                )
            assertTrue(runCatching { failing.fullImage("ses-1", "/repo", "a1", "missing") }.exceptionOrNull() is MessageError.Http)

            val withoutImage =
                createSync(
                    listMessageTail = { _, _ -> MessagePage(emptyList(), olderCursor = null, hasOlder = false) },
                    getMessage = { _, _, _ -> toolAssistant("") },
                )
            assertTrue(runCatching { withoutImage.fullImage("ses-1", "/repo", "a1", ToolResultImages.id("prt-tool", 0)) }.isFailure)
        }

    private fun toolAssistant(dataUrl: String) =
        Message(
            id = "a1",
            role = MessageRole.Assistant,
            text = "",
            parts =
                listOf(
                    MessagePart.Tool(
                        toolName = "read",
                        status = ToolStatus.Completed,
                        id = "prt-tool",
                        images =
                            listOf(
                                MessagePart.File(
                                    id = ToolResultImages.id("prt-tool", 0),
                                    filename = "shot.png",
                                    mime = "image/png",
                                    kind = AttachmentKind.Image,
                                    dataUrl = dataUrl,
                                ),
                            ),
                    ),
                ),
            sentAtEpochMs = 1_000L,
            completedAtEpochMs = 2_000L,
        )

    private fun userWithFile(dataUrl: String) =
        Message(
            id = "u1",
            role = MessageRole.User,
            text = "",
            parts =
                listOf(
                    MessagePart.File(
                        id = "prt-file",
                        filename = "photo.png",
                        mime = "image/png",
                        kind = AttachmentKind.Image,
                        dataUrl = dataUrl,
                    ),
                ),
            sentAtEpochMs = 500L,
        )

    private fun createSync(
        listMessageTail: MessageTailFetcher,
        listOlderMessages: MessageOlderFetcher = { _, _, _ ->
            MessagePage(emptyList(), null, false)
        },
        sessionCache: dev.opencode.mobile.domain.sessioncache.SessionCache =
            dev.opencode.mobile.domain.sessioncache.NoOpSessionCache,
        getMessage: MessageFetcher? = null,
    ): MessageSync {
        val eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(dev.opencode.mobile.domain.event.EventStreamConnectionState.Connected)
        val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        return MessageSync(
            listMessageTail = listMessageTail,
            listOlderMessages = listOlderMessages,
            getMessage = getMessage,
            engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
            eventStream = eventStream,
            liveStoreScope = scope,
            syncCoordinator =
                testSyncCoordinator(
                    eventStream = eventStream,
                    scope = scope.coroutineScope,
                    nowMs = { testDispatcher.scheduler.currentTime },
                    wait = { kotlinx.coroutines.delay(1) },
                ),
            nowMs = { testDispatcher.scheduler.currentTime },
            sessionCache = sessionCache,
        )
    }

    private fun user(id: String) = Message(id = id, role = MessageRole.User, text = id)

    private fun assistant(id: String) = Message(id = id, role = MessageRole.Assistant, text = id)

    private companion object {
        const val PNG_URL = "data:image/png;base64,iVBORw0KGgo="
    }
}

private class SeededSessionCache(
    private val seed: List<Message>,
) : dev.opencode.mobile.domain.sessioncache.SessionCache {
    override suspend fun seedSessions(worktree: String) = null

    override suspend fun rememberSessions(
        worktree: String,
        sessions: List<dev.opencode.mobile.domain.session.Session>,
    ) = Unit

    override suspend fun seedActivity(directory: String) = null

    override suspend fun rememberActivity(
        directory: String,
        entries: List<dev.opencode.mobile.domain.sessioncache.SessionActivityCacheEntry>,
    ) = Unit

    override fun openMessages(
        sessionId: String,
        worktree: String,
    ) = object : dev.opencode.mobile.domain.sessioncache.SessionMessageCacheHandle {
        override suspend fun seed(): List<Message> = seed

        override fun note(messages: List<Message>) = Unit

        override suspend fun flush() = Unit

        override fun close() = Unit
    }

    override suspend fun purgeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun clearAll() = Unit

    override suspend fun estimatedSizeBytes(): Long = 0L
}
