package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewReporterTest {
    private val testDispatcher = StandardTestDispatcher()
    private val eventStream = FakeForegroundEventStream()
    private val liveStore = RecordingSessionLiveStore()

    @Test
    fun focusOnUnreadSession_postsOncePerWatermark() =
        runTest(testDispatcher) {
            val repository = ViewRepository(unread(idle = 30L, viewed = 10L))
            val reporter = reporter(repository)

            reporter.focus(SESSION)
            advanceUntilIdle()
            assertEquals(listOf(SESSION to 30L), repository.posts)
            assertEquals(listOf(SESSION to 30L), liveStore.applied)

            // A stale server read (still unread at the same watermark) never re-posts.
            repository.applyPosts = false
            repository.session = unread(idle = 30L, viewed = 10L)
            reporter.focus(SESSION)
            eventStream.emit(OpencodeEvent(type = "stream.reconnected"))
            advanceUntilIdle()
            reporter.release(SESSION)

            assertEquals(listOf(SESSION to 30L), repository.posts)
        }

    @Test
    fun focusOnReadSession_doesNotPost() =
        runTest(testDispatcher) {
            val repository = ViewRepository(unread(idle = 30L, viewed = 30L))
            val reporter = reporter(repository)

            reporter.focus(SESSION)
            advanceUntilIdle()
            reporter.release(SESSION)

            assertEquals(1, repository.getSessionCalls)
            assertEquals(emptyList<Pair<String, Long>>(), repository.posts)
        }

    @Test
    fun v1Server_neverTouchesTheNetwork() =
        runTest(testDispatcher) {
            val repository = ViewRepository(unread(idle = 30L, viewed = null))
            val reporter = reporter(repository, ServerProtocol.V1)

            reporter.focus(SESSION)
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = SESSION))
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            advanceUntilIdle()
            reporter.release(SESSION)

            assertEquals(0, repository.getSessionCalls)
            assertEquals(emptyList<Pair<String, Long>>(), repository.posts)
        }

    @Test
    fun childSession_isSkipped() =
        runTest(testDispatcher) {
            val repository = ViewRepository(unread(idle = 30L, viewed = null).copy(parentId = "ses-parent"))
            val reporter = reporter(repository)

            reporter.focus(SESSION)
            advanceUntilIdle()
            reporter.release(SESSION)

            assertEquals(emptyList<Pair<String, Long>>(), repository.posts)
        }

    @Test
    fun runEndingWhileFocused_isAcknowledged() =
        runTest(testDispatcher) {
            val repository = ViewRepository(unread(idle = 10L, viewed = 10L))
            val reporter = reporter(repository)
            reporter.focus(SESSION)
            advanceUntilIdle()
            assertEquals(emptyList<Pair<String, Long>>(), repository.posts)

            repository.session = unread(idle = 40L, viewed = 10L)
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = SESSION))
            advanceUntilIdle()
            assertEquals(listOf(SESSION to 40L), repository.posts)

            // The run-end refresh in the list store also re-checks (e.g. GET raced the server write).
            repository.session = unread(idle = 55L, viewed = 40L)
            liveStore.known.value = mapOf(SESSION to repository.session)
            advanceUntilIdle()
            reporter.release(SESSION)

            assertEquals(listOf(SESSION to 40L, SESSION to 55L), repository.posts)
        }

    @Test
    fun otherSessionRunEnd_isIgnored() =
        runTest(testDispatcher) {
            val repository = ViewRepository(unread(idle = 10L, viewed = 10L))
            val reporter = reporter(repository)
            reporter.focus(SESSION)
            advanceUntilIdle()

            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-other"))
            advanceUntilIdle()
            reporter.release(SESSION)

            assertEquals(1, repository.getSessionCalls)
        }

    @Test
    fun failedPost_retriesWithDoublingBackoff() =
        runTest(testDispatcher) {
            val repository = ViewRepository(unread(idle = 30L, viewed = null))
            repository.failuresRemaining = 2
            val reporter = reporter(repository)

            reporter.focus(SESSION)
            runCurrent()
            assertEquals(1, repository.posts.size)
            advanceTimeBy(DefaultSessionViewReporter.INITIAL_BACKOFF_MS - 1)
            runCurrent()
            assertEquals(1, repository.posts.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, repository.posts.size)
            advanceTimeBy(DefaultSessionViewReporter.INITIAL_BACKOFF_MS * 2)
            runCurrent()
            assertEquals(3, repository.posts.size)
            advanceUntilIdle()
            reporter.release(SESSION)

            assertEquals(listOf(SESSION to 30L), liveStore.applied)
            assertEquals(3, repository.posts.size)
        }

    @Test
    fun exhaustedRetryBudget_waitsForReconnectThenRetries() =
        runTest(testDispatcher) {
            val repository = ViewRepository(unread(idle = 30L, viewed = null))
            repository.failuresRemaining = Int.MAX_VALUE
            val reporter = reporter(repository)
            val burst = 1 + DefaultSessionViewReporter.MAX_RETRY_ATTEMPTS

            reporter.focus(SESSION)
            runCurrent()
            assertEquals(1, repository.posts.size)
            // 250 + 500 + 1000 + 2000 + 4000 ms of backoff, then the burst gives up.
            advanceTimeBy(7_750L)
            runCurrent()
            assertEquals(burst, repository.posts.size)
            assertEquals(burst, repository.getSessionCalls)

            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(burst, repository.posts.size)
            assertEquals(burst, repository.getSessionCalls)

            // Foreground return / reconnect starts a fresh burst with reset backoff.
            repository.failuresRemaining = 1
            eventStream.emit(OpencodeEvent(type = "server.connected"))
            runCurrent()
            assertEquals(burst + 1, repository.posts.size)
            advanceTimeBy(DefaultSessionViewReporter.INITIAL_BACKOFF_MS)
            runCurrent()
            reporter.release(SESSION)

            assertEquals(burst + 2, repository.posts.size)
            assertEquals(listOf(SESSION to 30L), liveStore.applied)
        }

    @Test
    fun release_stopsRetrying() =
        runTest(testDispatcher) {
            val repository = ViewRepository(unread(idle = 30L, viewed = null))
            repository.failuresRemaining = Int.MAX_VALUE
            val reporter = reporter(repository)

            reporter.focus(SESSION)
            runCurrent()
            reporter.release("ses-other")
            advanceTimeBy(DefaultSessionViewReporter.INITIAL_BACKOFF_MS)
            runCurrent()
            assertEquals(2, repository.posts.size)

            reporter.release(SESSION)
            advanceTimeBy(60_000L)
            runCurrent()

            assertEquals(2, repository.posts.size)
        }

    @Test
    fun clearCache_stopsAndForgetsWatermarks() =
        runTest(testDispatcher) {
            val repository = ViewRepository(unread(idle = 30L, viewed = null))
            repository.applyPosts = false
            val reporter = reporter(repository)
            reporter.focus(SESSION)
            advanceUntilIdle()

            reporter.clearCache()
            reporter.focus(SESSION)
            advanceUntilIdle()
            reporter.release(SESSION)

            assertEquals(listOf(SESSION to 30L, SESSION to 30L), repository.posts)
        }

    private fun reporter(
        repository: SessionRepository,
        protocol: ServerProtocol = ServerProtocol.V2,
    ): DefaultSessionViewReporter =
        DefaultSessionViewReporter(
            sessionRepository = repository,
            sessionLiveStore = liveStore,
            eventStream = eventStream,
            liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher)),
            capabilities = { ServerCapabilities.forProtocol(protocol) },
        )

    private fun unread(
        idle: Long,
        viewed: Long?,
    ): Session = Session(id = SESSION, title = "Chat", idleAtEpochMs = idle, viewedAtEpochMs = viewed)

    private companion object {
        const val SESSION = "ses-1"
    }
}

private class RecordingSessionLiveStore : SessionLiveStore {
    val known = MutableStateFlow<Map<String, Session>>(emptyMap())
    val applied = mutableListOf<Pair<String, Long>>()

    override fun observeSessions(directory: String): Flow<SessionListSnapshot> = emptyFlow()

    override fun clearCache() = Unit

    override fun observeKnownSession(sessionId: String): Flow<Session?> = known.map { it[sessionId] }

    override fun applySessionViewed(
        sessionId: String,
        idleAtEpochMs: Long,
    ) {
        applied += sessionId to idleAtEpochMs
    }
}

private class ViewRepository(
    var session: Session,
) : SessionRepository by unsupportedSessionRepository() {
    var getSessionCalls = 0
    val posts = mutableListOf<Pair<String, Long>>()
    var failuresRemaining = 0
    var applyPosts = true

    override suspend fun getSession(
        id: String,
        directory: String?,
    ): Session {
        getSessionCalls += 1
        return session
    }

    override suspend fun markSessionViewed(
        sessionId: String,
        idleAtEpochMs: Long,
    ) {
        posts += sessionId to idleAtEpochMs
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("view failed")
        }
        if (applyPosts) session = session.withViewed(idleAtEpochMs)
    }
}

private fun unsupportedSessionRepository(): SessionRepository =
    Proxy.newProxyInstance(
        SessionRepository::class.java.classLoader,
        arrayOf(SessionRepository::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as SessionRepository
