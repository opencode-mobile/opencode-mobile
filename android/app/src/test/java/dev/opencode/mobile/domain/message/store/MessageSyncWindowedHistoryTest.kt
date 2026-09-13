package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.live.LiveStoreScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
            sync.observeMessages("ses-1", "/repo")
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
                sync.observeMessages("ses-1", "/repo")
                    .first { update ->
                        update is MessageListUpdate.Messages && !update.isFromCache
                    } as MessageListUpdate.Messages

            assertEquals(listOf("x", "y"), last.messages.map { it.id })
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
            sync.observeMessages("ses-1", "/repo")
                .onEach { updates += it }
                .first { update ->
                    update is MessageListUpdate.Messages &&
                        (update as MessageListUpdate.Messages).messages.firstOrNull()?.role ==
                        MessageRole.User
                }
            advanceUntilIdle()

            val published =
                updates.filterIsInstance<MessageListUpdate.Messages>().map { it.messages.map { message -> message.id } }
            assertEquals(listOf("a20"), published.first())
            assertEquals(listOf("u1", "a20"), published.last())
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

            val refreshed = sync.refreshMessages("ses-1", "/repo")

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
                    pollIntervalMs = 60_000L,
                    recoveryDelay = { },
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

            sync.observeMessages("ses-1", "/repo").first()
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
                    eventStream = FakeForegroundEventStream(),
                    liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
                    pollIntervalMs = 60_000L,
                    recoveryDelay = { },
                    nowMs = { testDispatcher.scheduler.currentTime },
                )

            sync.observeMessages("ses-1", "/repo").first()
            advanceUntilIdle()
            sync.refreshMessages("ses-1", "/repo")

            assertEquals(0, fullCalls)
            assertTrue(tailCalls >= 2)
        }

    private fun createSync(
        listMessageTail: MessageTailFetcher,
        listOlderMessages: MessageOlderFetcher = { _, _, _ ->
            MessagePage(emptyList(), null, false)
        },
        sessionCache: dev.opencode.mobile.domain.sessioncache.SessionCache =
            dev.opencode.mobile.domain.sessioncache.NoOpSessionCache,
    ): MessageSync =
        MessageSync(
            listMessageTail = listMessageTail,
            listOlderMessages = listOlderMessages,
            engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
            eventStream = FakeForegroundEventStream(),
            liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
            pollIntervalMs = 60_000L,
            recoveryDelay = { },
            nowMs = { testDispatcher.scheduler.currentTime },
            sessionCache = sessionCache,
        )

    private fun user(id: String) = Message(id = id, role = MessageRole.User, text = id)

    private fun assistant(id: String) = Message(id = id, role = MessageRole.Assistant, text = id)
}

private class SeededSessionCache(
    private val seed: List<Message>,
) : dev.opencode.mobile.domain.sessioncache.SessionCache {
    override suspend fun seedSessions(worktree: String) = null

    override suspend fun rememberSessions(
        worktree: String,
        sessions: List<dev.opencode.mobile.domain.session.Session>,
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
