package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.sessioncache.SessionActivityCacheEntry
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sessioncache.SessionMessageCacheHandle
import dev.opencode.mobile.domain.sync.testSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Releasing closed sessions' transcripts ([TranscriptRetentionPolicy]). Mirrored on iOS by
 * `MessageSyncTranscriptReleaseTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageSyncTranscriptReleaseTest {
    private val testDispatcher = StandardTestDispatcher()
    private val store = InMemoryMessageStore(MessageDomainAdapter())
    private val engine = SessionMessageEngine(store)
    private val eventStream =
        FakeForegroundEventStream().apply { setConnectionState(EventStreamConnectionState.Connected) }
    private val cache = RecordingSessionCache()
    private val tailCalls = mutableListOf<String>()
    private val sync = createSync()

    @Test
    fun closedSession_isReleasedAfterGracePeriod() =
        runTest(testDispatcher) {
            val job = observe("ses-1")
            runCurrent()
            assertEquals(listOf("ses-1-a", "ses-1-b"), engine.messages(slot("ses-1")).map { it.id })

            job.cancel()
            advanceTimeBy(TranscriptRetentionPolicy.CLOSED_SESSION_GRACE_MS - 1)
            runCurrent()
            assertTrue(sync.retainsTranscript("ses-1", DIR))

            advanceTimeBy(2)
            runCurrent()
            assertReleased("ses-1")
        }

    @Test
    fun warmCap_releasesOldestOfFourClosedSessions() =
        runTest(testDispatcher) {
            val ids = listOf("ses-1", "ses-2", "ses-3", "ses-4")
            for (id in ids) {
                val job = observe(id)
                runCurrent()
                job.cancel()
                advanceTimeBy(1_000)
                runCurrent()
            }

            assertReleased("ses-1")
            ids.drop(1).forEach { assertTrue("$it stays warm", sync.retainsTranscript(it, DIR)) }
        }

    @Test
    fun sessionWithAnyObserverLeft_isKept() =
        runTest(testDispatcher) {
            // Session Chat on the back stack, a tool-group screen over it, and a parent's
            // activity card watching it all collect the same store flow.
            val backStackChat = observe("ses-1")
            val toolGroup = observe("ses-1")
            val activityCard = observe("ses-1")
            runCurrent()

            toolGroup.cancel()
            activityCard.cancel()
            fillWarmCapWithOtherClosedSessions()
            advanceTimeBy(TranscriptRetentionPolicy.CLOSED_SESSION_GRACE_MS * 2)
            runCurrent()
            assertTrue(sync.retainsTranscript("ses-1", DIR))
            assertEquals(2, engine.messages(slot("ses-1")).size)

            sync.releaseClosedSessions()
            runCurrent()
            assertTrue(sync.retainsTranscript("ses-1", DIR))
            backStackChat.cancel()
        }

    @Test
    fun parentActivityCard_keepsRunningChildAfterChildScreenCloses() =
        runTest(testDispatcher) {
            val parent = observe("parent")
            val childScreen = observe("child")
            val card = observe("child")
            runCurrent()

            childScreen.cancel()

            runCurrent()
            sync.releaseClosedSessions()
            advanceTimeBy(TranscriptRetentionPolicy.CLOSED_SESSION_GRACE_MS * 2)
            runCurrent()
            assertTrue(sync.retainsTranscript("child", DIR))

            card.cancel()

            runCurrent()
            sync.releaseClosedSessions()
            runCurrent()
            assertReleased("child")
            assertTrue(sync.retainsTranscript("parent", DIR))
            parent.cancel()
        }

    @Test
    fun reopeningReleasedSession_paintsCacheThenServerLikeFirstOpen() =
        runTest(testDispatcher) {
            cache.noted["ses-1"] = transcript("ses-1")
            val first = mutableListOf<MessageListUpdate>()
            val firstJob = observe("ses-1", first)
            runCurrent()
            firstJob.cancel()
            runCurrent()
            sync.releaseClosedSessions()
            runCurrent()
            assertReleased("ses-1")

            val second = mutableListOf<MessageListUpdate>()
            val secondJob = observe("ses-1", second)
            runCurrent()

            assertEquals(first.map { it.summary() }, second.map { it.summary() })
            val firstFrame = second.first() as MessageListUpdate.Messages
            assertTrue("the first frame is the cached paint", firstFrame.isFromCache)
            assertEquals(listOf("ses-1-a", "ses-1-b"), engine.messages(slot("ses-1")).map { it.id })
            assertEquals(listOf("ses-1", "ses-1"), tailCalls)
            secondJob.cancel()
        }

    @Test
    fun reopeningWithinGrace_paintsHeldTranscriptWithoutCacheRoundTrip() =
        runTest(testDispatcher) {
            val firstJob = observe("ses-1")
            runCurrent()
            firstJob.cancel()
            advanceTimeBy(10_000)
            runCurrent()

            val updates = mutableListOf<MessageListUpdate>()
            val job = observe("ses-1", updates)
            runCurrent()
            val painted = updates.first() as MessageListUpdate.Messages
            assertFalse(painted.isFromCache)
            assertEquals(listOf("ses-1-a", "ses-1-b"), painted.messages.map { it.id })
            job.cancel()
        }

    @Test
    fun clearCache_freesOpenAndClosedSessions() =
        runTest(testDispatcher) {
            val closed = observe("closed")
            val open = observe("open")
            runCurrent()
            closed.cancel()
            runCurrent()

            sync.clearCache()
            runCurrent()

            assertReleased("closed")
            assertReleased("open")
            assertFalse(store.retains(slot("open")))
            open.cancel()
            advanceTimeBy(TranscriptRetentionPolicy.CLOSED_SESSION_GRACE_MS * 2)
            runCurrent()
            assertReleased("open")
        }

    @Test
    fun memoryPressure_releasesClosedSessionsAtOnceAndKeepsOpenOnes() =
        runTest(testDispatcher) {
            val closed = observe("closed")
            val open = observe("open")
            runCurrent()
            closed.cancel()
            runCurrent()
            assertTrue(sync.retainsTranscript("closed", DIR))

            sync.releaseClosedSessions()
            runCurrent()

            assertReleased("closed")
            assertEquals(listOf("open-a", "open-b"), engine.messages(slot("open")).map { it.id })
            open.cancel()
        }

    @Test
    fun eventsForReleasedSession_doNotBuildPartialTranscript() =
        runTest(testDispatcher) {
            // Control: while observed, the same event lands in the store.
            val job = observe("ses-1")
            runCurrent()
            val revisionBefore = engine.revision(slot("ses-1"))
            eventStream.emit(messageUpdated("ses-1", "live-1"))
            runCurrent()
            assertTrue(engine.revision(slot("ses-1")) > revisionBefore)

            job.cancel()

            runCurrent()
            sync.releaseClosedSessions()
            runCurrent()
            assertReleased("ses-1")

            eventStream.emit(messageUpdated("ses-1", "live-2"))
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            runCurrent()
            assertReleased("ses-1")
        }

    @Test
    fun observerReturningDuringRelease_loadsCleanTranscript() =
        runTest(testDispatcher) {
            val job = observe("ses-1")
            runCurrent()
            job.cancel()
            runCurrent()
            // Release while the share coroutine is still inside its stop timeout, then open
            // again before the release finishes.
            sync.releaseClosedSessions()
            val updates = mutableListOf<MessageListUpdate>()
            val again = observe("ses-1", updates)
            runCurrent()

            val frames = updates.filterIsInstance<MessageListUpdate.Messages>()
            assertTrue(frames.first().isFromCache)
            assertEquals(listOf("ses-1-a", "ses-1-b"), engine.messages(slot("ses-1")).map { it.id })
            again.cancel()
        }

    private fun TestScope.fillWarmCapWithOtherClosedSessions() {
        repeat(TranscriptRetentionPolicy.MAX_WARM_CLOSED_SESSIONS + 1) { index ->
            val other = observe("other-$index")
            testScheduler.runCurrent()
            other.cancel()
            testScheduler.runCurrent()
        }
    }

    private fun TestScope.observe(
        sessionId: String,
        into: MutableList<MessageListUpdate> = mutableListOf(),
    ): Job = backgroundScope.launch { sync.observeMessages(sessionId, DIR).collect { into += it } }

    private fun assertReleased(sessionId: String) {
        assertFalse("$sessionId still held", sync.retainsTranscript(sessionId, DIR))
        assertTrue(engine.messages(slot(sessionId)).isEmpty())
        assertFalse(store.retains(slot(sessionId)))
    }

    private fun slot(sessionId: String) = MessageSessionKey.of(PROFILE, DIR, sessionId).storeSlot

    private fun MessageListUpdate.summary(): String =
        when (this) {
            is MessageListUpdate.Messages -> "messages:${messages.map { it.id }}:cache=$isFromCache"
            else -> this::class.simpleName.orEmpty()
        }

    private fun messageUpdated(
        sessionId: String,
        messageId: String,
    ) = OpencodeEvent(
        type = "message.updated",
        sessionId = sessionId,
        rawProperties =
            buildJsonObject {
                putJsonObject("info") {
                    put("id", messageId)
                    put("sessionID", sessionId)
                    put("role", "user")
                    putJsonObject("time") { put("created", 1) }
                }
            },
    )

    private fun createSync(): MessageSync {
        val scope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        return MessageSync(
            listMessageTail = { sessionId, _ ->
                tailCalls += sessionId
                MessagePage(messages = transcript(sessionId), olderCursor = null, hasOlder = false)
            },
            engine = engine,
            eventStream = eventStream,
            liveStoreScope = scope,
            syncCoordinator =
                testSyncCoordinator(
                    eventStream = eventStream,
                    scope = scope.coroutineScope,
                    nowMs = { testDispatcher.scheduler.currentTime },
                    wait = { delay(1) },
                ),
            connectionProfileIdProvider = { PROFILE },
            nowMs = { testDispatcher.scheduler.currentTime },
            sessionCache = cache,
        )
    }

    private companion object {
        const val DIR = "/Users/example/project"
        const val PROFILE = "profile-1"

        fun transcript(sessionId: String) =
            listOf(
                Message(id = "$sessionId-a", role = MessageRole.User, text = "question"),
                Message(id = "$sessionId-b", role = MessageRole.Assistant, text = "answer"),
            )
    }
}

/** Disk cache stand-in: remembers the last noted frame per session and seeds from it. */
private class RecordingSessionCache : SessionCache {
    val noted = mutableMapOf<String, List<Message>>()

    override suspend fun seedSessions(worktree: String) = null

    override suspend fun rememberSessions(
        worktree: String,
        sessions: List<dev.opencode.mobile.domain.session.Session>,
    ) = Unit

    override suspend fun seedActivity(directory: String) = null

    override suspend fun rememberActivity(
        directory: String,
        entries: List<SessionActivityCacheEntry>,
    ) = Unit

    override fun openMessages(
        sessionId: String,
        worktree: String,
    ) = object : SessionMessageCacheHandle {
        override suspend fun seed(): List<Message>? = noted[sessionId]

        override fun note(messages: List<Message>) {
            noted[sessionId] = messages
        }

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
