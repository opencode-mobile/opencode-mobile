package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.live.LiveStoreScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
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
