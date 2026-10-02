package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionActivityEntry
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import dev.opencode.mobile.domain.session.SessionActivitySource
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
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
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
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
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
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            store.setFocusedSession(null)
            statusStore.emit("/repo", idleSnapshot())
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
    fun staleRelease_afterReturningToParent_keepsParentFocused() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.setFocusedSession("parent", "/repo")
            store.notifyPromptSent("parent", "/repo")
            statusStore.emit("/repo", runningSnapshot("parent"))
            advanceUntilIdle()
            // Open the child, then "Back to main session": the parent re-focuses before the
            // leaving child (and the earlier parent entry) release their focus.
            store.setFocusedSession("child", "/repo")
            store.releaseFocusedSession("parent")
            store.setFocusedSession("parent", "/repo")
            store.releaseFocusedSession("child")
            statusStore.emit("/repo", idleSnapshot())
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().isEmpty())
        }

    @Test
    fun switchingFocusToAnotherSession_whileBusy_marksPriorReady() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.setFocusedSession("parent", "/repo")
            statusStore.emit("/repo", runningSnapshot("parent"))
            advanceUntilIdle()
            store.setFocusedSession("child", "/repo")
            store.releaseFocusedSession("parent")
            statusStore.emit("/repo", idleSnapshot())
            advanceUntilIdle()
            job.cancel()

            assertEquals(setOf("parent"), emissions.last())
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

            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
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
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
            advanceUntilIdle()
            assertTrue(emissions.last().contains("ses-1"))

            store.setFocusedSession("ses-1", "/repo")
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().isEmpty())
        }

    @Test
    fun openReady_thenStatusEvent_thenLeave_doesNotRestampReady() =
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

            store.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            // Idle sessions are often absent from the status map.
            statusStore.emit("/repo", idleSnapshot())
            advanceUntilIdle()
            assertTrue(emissions.last().contains("ses-1"))

            // User opens Ready session to clear the pill.
            store.setFocusedSession("ses-1", "/repo")
            advanceUntilIdle()
            assertTrue(emissions.last().isEmpty())

            // Speculative session.status while focused must not re-arm Ready after leave.
            eventStream.emit(OpencodeEvent(type = "session.status", sessionId = "ses-1"))
            advanceUntilIdle()
            store.setFocusedSession(null)
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
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
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
            advanceUntilIdle()
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            job.cancel()

            assertTrue(readyIds.last().contains("ses-1"))
        }

    @Test
    fun bindProfile_switchAway_doesNotMarkReadyFromOtherServer() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.bindProfile("profile-a")
            store.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            store.bindProfile("profile-b")
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().isEmpty())
        }

    @Test
    fun bindProfile_parksWatch_newStoreOnOriginalProfile_idleCatchUpSetsReady() =
        runTest(testDispatcher) {
            val persistence = InMemorySessionReadyPersistence()
            val first = createStore(FakeReadyStatusStore(), persistence = persistence)
            first.bindProfile("profile-a")
            first.observeReadyIds("/repo").first()
            first.notifyPromptSent("ses-1", "/repo")
            first.bindProfile("profile-b")

            val statusStore = FakeReadyStatusStore()
            val second = createStore(statusStore, persistence = persistence)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    second.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()
            second.bindProfile("profile-a")
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().contains("ses-1"))
        }

    @Test
    fun bindProfile_switchAwayAndBack_idleCatchUpSetsReady() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            val emissions = mutableListOf<Set<String>>()
            val job =
                launch {
                    store.observeReadyIds("/repo").collect { emissions.add(it) }
                }
            advanceUntilIdle()

            store.bindProfile("profile-a")
            store.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            store.bindProfile("profile-b")
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
            advanceUntilIdle()
            store.bindProfile("profile-a")
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
            advanceUntilIdle()
            job.cancel()

            assertTrue(emissions.last().contains("ses-1"))
        }

    @Test
    fun clearCache_emptiesReady() =
        runTest(testDispatcher) {
            val statusStore = FakeReadyStatusStore()
            val store = createStore(statusStore)
            store.observeReadyIds("/repo").first()
            store.notifyPromptSent("ses-1", "/repo")
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
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
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
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
            statusStore.emit("/repo", runningSnapshot())
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
            statusStore2.emit("/repo", idleSnapshot())
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
            statusStore.emit("/repo", runningSnapshot())
            advanceUntilIdle()
            statusStore.emit("/repo", idleSnapshot())
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

    private fun runningSnapshot(sessionId: String = "ses-1"): SessionActivitySnapshot =
        SessionActivitySnapshot(
            entries =
                mapOf(
                    sessionId to
                        SessionActivityEntry(
                            activity = SessionActivity.Running,
                            source = SessionActivitySource.Server,
                            observedAtMs = 0L,
                        ),
                ),
            seeded = true,
        )

    private fun idleSnapshot(): SessionActivitySnapshot = SessionActivitySnapshot(entries = emptyMap(), seeded = true)
}

private class FakeReadyStatusStore : SessionStatusLiveStore {
    private val flows = mutableMapOf<String, MutableSharedFlow<SessionActivitySnapshot>>()

    fun emit(
        directory: String,
        snapshot: SessionActivitySnapshot,
    ) {
        flow(directory).tryEmit(snapshot)
    }

    override fun observeActivity(directory: String): Flow<SessionActivitySnapshot> = flow(directory)

    override fun clearCache() = Unit

    private fun flow(directory: String): MutableSharedFlow<SessionActivitySnapshot> =
        flows.getOrPut(directory) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 16)
        }
}
