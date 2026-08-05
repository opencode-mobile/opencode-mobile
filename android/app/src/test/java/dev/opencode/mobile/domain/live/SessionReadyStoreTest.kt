package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionReadyStoreTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun notifyPromptSent_idleAfterBusyWhileUnfocused_setsReady() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            statusStore.emit("/repo", mapOf("ses-1" to status("idle")))
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().contains("ses-1"))
        }

    @Test
    fun idleWhileFocused_doesNotSetReady() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.setFocusedSession("ses-1", "/repo")
            store.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            statusStore.emit("/repo", mapOf("ses-1" to status("idle")))
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().isEmpty())
        }

    @Test
    fun focusedBusy_thenLeave_thenIdle_setsReady() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.setFocusedSession("ses-1", "/repo")
            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            store.setFocusedSession(null)
            statusStore.emit("/repo", mapOf("ses-1" to status("idle")))
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().contains("ses-1"))
        }

    @Test
    fun focusedStatusEvent_thenLeave_thenIdleEvent_setsReadyWithoutBusySnapshot() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val eventStream = FakeForegroundEventStream()
            val store = createStore(statusStore, eventStream)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.setFocusedSession("ses-1", "/repo")
            advanceUntilIdle()
            // Debounce coalesced away the busy snapshot; only SSE events arrive.
            eventStream.emit(OpencodeEvent(type = "session.status", sessionId = "ses-1"))
            advanceUntilIdle()
            store.setFocusedSession(null)
            advanceUntilIdle()
            eventStream.emit(OpencodeEvent(type = "session.idle", sessionId = "ses-1"))
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().contains("ses-1"))
        }

    @Test
    fun homeOnlyBusy_neverWatched_staysQuiet() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            statusStore.emit("/repo", mapOf("ses-1" to status("idle")))
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().isEmpty())
        }

    @Test
    fun setFocusedSession_clearsReady() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            statusStore.emit("/repo", mapOf("ses-1" to status("idle")))
            advanceUntilIdle()
            assertTrue(emissions.last().contains("ses-1"))

            store.setFocusedSession("ses-1", "/repo")
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().isEmpty())
        }

    @Test
    fun readySurvivesLaterBusyUntilFocus() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            val readyIds = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { readyIds.add(it) }
                }
            advanceUntilIdle()

            store.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            statusStore.emit("/repo", mapOf("ses-1" to status("idle")))
            advanceUntilIdle()
            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            job.cancel()

            assertTrue(readyIds.last().contains("ses-1"))
        }

    @Test
    fun clearCache_emptiesReady() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            store.observeReadyIds("/repo").first()
            store.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            statusStore.emit("/repo", mapOf("ses-1" to status("idle")))
            advanceUntilIdle()

            store.clearCache()
            advanceUntilIdle()

            assertEquals(emptySet<String>(), store.observeReadyIds("/repo").first())
        }

    @Test
    fun readySurvivesRestartViaPersistence() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val persistence = InMemorySessionReadyPersistence()
            val first = createStore(statusStore, persistence = persistence)
            first.observeReadyIds("/repo").first()
            first.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            statusStore.emit("/repo", mapOf("ses-1" to status("idle")))
            advanceUntilIdle()

            val second = createStore(FakeReadyStatusStore(), persistence = persistence)
            assertTrue(second.observeReadyIds("/repo").first().contains("ses-1"))
        }

    @Test
    fun watchedSurvivesRestart_thenIdleCatchUp_setsReady() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val persistence = InMemorySessionReadyPersistence()
            val first = createStore(statusStore, persistence = persistence)
            first.observeReadyIds("/repo").first()
            first.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            // Kill before idle: only watched is persisted.

            val statusStore2 = FakeReadyStatusStore()
            val second = createStore(statusStore2, persistence = persistence)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    second.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()
            // Catch-up: session already idle after restart.
            statusStore2.emit("/repo", emptyMap())
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().contains("ses-1"))
        }

    @Test
    fun readyExpiresAfterTtl() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val persistence = InMemorySessionReadyPersistence()
            var now = 1_000_000L
            val clock = SessionReadyClock { now }
            val first = createStore(statusStore, persistence = persistence, clock = clock)
            first.observeReadyIds("/repo").first()
            first.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", mapOf("ses-1" to status("busy")))
            advanceUntilIdle()
            statusStore.emit("/repo", mapOf("ses-1" to status("idle")))
            advanceUntilIdle()

            now += DefaultSessionReadyStore.READY_TTL_MS
            val second = createStore(FakeReadyStatusStore(), persistence = persistence, clock = clock)
            assertEquals(emptySet<String>(), second.observeReadyIds("/repo").first())
        }

    private fun createStore(
        statusStore: SessionStatusLiveStore,
        eventStream: FakeForegroundEventStream = FakeForegroundEventStream(),
        persistence: SessionReadyPersistence = InMemorySessionReadyPersistence(),
        clock: SessionReadyClock = SessionReadyClock { System.currentTimeMillis() },
    ): DefaultSessionReadyStore =
        DefaultSessionReadyStore(
            sessionStatusLiveStore = statusStore,
            eventStream = eventStream,
            liveStoreScope = LiveStoreScope(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + testDispatcher)),
            persistence = persistence,
            clock = clock,
        )

    private fun status(type: String): SessionStatus = SessionStatus(sessionId = "ses-1", raw = mapOf("type" to type))
}

private class FakeReadyStatusStore : SessionStatusLiveStore {
    private val flows = mutableMapOf<String, MutableSharedFlow<Map<String, SessionStatus>>>()

    fun emit(
        directory: String,
        statuses: Map<String, SessionStatus>,
    ) {
        flow(directory).tryEmit(statuses)
    }

    override fun observeStatuses(directory: String): Flow<Map<String, SessionStatus>> = flow(directory)

    override fun clearCache() = Unit

    private fun flow(directory: String): MutableSharedFlow<Map<String, SessionStatus>> =
        flows.getOrPut(directory) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 16)
        }
}
