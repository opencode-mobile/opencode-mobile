package dev.opencode.hexcode.home

import androidx.lifecycle.viewModelScope
import dev.opencode.hexcode.session.SessionSetup
import dev.opencode.hexcode.session.StatusSummary
import dev.opencode.hexcode.session.TodaySessionDemo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeDictation : Dictation {
        override val level = 0f
        var recording = false
        var drops = 0
        val texts = ArrayDeque<String>()
        var gate: CompletableDeferred<Unit>? = null

        override fun start() {
            recording = true
        }

        override fun end(): FloatArray? {
            if (!recording) return null
            recording = false
            return FloatArray(16_000)
        }

        override fun drop() {
            recording = false
            drops += 1
        }

        override suspend fun transcribe(samples: FloatArray): String {
            gate?.await()
            return texts.removeFirstOrNull() ?: error("No speech was detected.")
        }
    }

    private var now = 0L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun holdThenReleaseEndsTheTakeIntoTheDraft() =
        runTest(dispatcher) {
            val dictation = FakeDictation().apply { texts += "Add a remember-me checkbox" }
            val vm = HomeViewModel(dictation) { now }
            vm.press()
            assertEquals(TalkMode.Holding, vm.uiState.value.talk)
            assertTrue(dictation.recording)
            now = 4_000
            vm.release(TalkRelease.End)
            assertEquals(TalkMode.Idle, vm.uiState.value.talk)
            assertEquals(
                ChunkState.Transcribing,
                vm.uiState.value.draft.chunks
                    .single()
                    .state,
            )
            advanceUntilIdle()
            assertEquals("Add a remember-me checkbox", vm.uiState.value.draft.text)
            assertEquals(4_000, vm.uiState.value.draft.durationMs)
        }

    @Test
    fun dragRightLocksAndATapPauses() =
        runTest(dispatcher) {
            val dictation = FakeDictation().apply { texts += "hands free" }
            val vm = HomeViewModel(dictation) { now }
            vm.press()
            vm.release(TalkRelease.Lock)
            assertEquals(TalkMode.Locked, vm.uiState.value.talk)
            assertTrue(dictation.recording)
            vm.tapLocked()
            advanceUntilIdle()
            assertEquals("hands free", vm.uiState.value.draft.text)
            assertEquals(TalkMode.Idle, vm.uiState.value.talk)
        }

    @Test
    fun aQuickTapLocksLikeADrag() =
        runTest(dispatcher) {
            val vm = HomeViewModel(FakeDictation()) { now }
            vm.press()
            vm.release(TalkRelease.Tap)
            assertEquals(TalkMode.Locked, vm.uiState.value.talk)
        }

    @Test
    fun dragLeftDropsTheTake() =
        runTest(dispatcher) {
            val dictation = FakeDictation()
            val vm = HomeViewModel(dictation) { now }
            vm.press()
            vm.release(TalkRelease.Drop)
            assertEquals(1, dictation.drops)
            assertTrue(vm.uiState.value.draft.isEmpty)
            assertEquals(HomeNotice.TakeDropped, vm.uiState.value.notice)
        }

    @Test
    fun theNextTakeRecordsWhileTheLastOneTranscribes() =
        runTest(dispatcher) {
            val dictation = FakeDictation().apply { texts += listOf("one", "two") }
            dictation.gate = CompletableDeferred()
            val vm = HomeViewModel(dictation) { now }
            vm.press()
            vm.release(TalkRelease.End)
            vm.press()
            assertTrue(vm.uiState.value.draft.isListening)
            assertEquals(
                ChunkState.Transcribing,
                vm.uiState.value.draft.chunks
                    .first()
                    .state,
            )
            vm.release(TalkRelease.End)
            dictation.gate?.complete(Unit)
            advanceUntilIdle()
            assertEquals("one two", vm.uiState.value.draft.text)
        }

    @Test
    fun aTakeWithNoSpeechLeavesNothing() =
        runTest(dispatcher) {
            val vm = HomeViewModel(FakeDictation()) { now }
            vm.press()
            vm.release(TalkRelease.End)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.draft.isEmpty)
        }

    @Test
    fun reRecordReplacesTheSelectedChunk() =
        runTest(dispatcher) {
            val dictation = FakeDictation().apply { texts += listOf("one", "two", "TWO") }
            val vm = HomeViewModel(dictation) { now }
            repeat(2) {
                vm.press()
                vm.release(TalkRelease.End)
                advanceUntilIdle()
            }
            vm.selectChunk(1)
            vm.rerecord()
            assertEquals(TalkMode.Locked, vm.uiState.value.talk)
            vm.tapLocked()
            advanceUntilIdle()
            assertEquals("one TWO", vm.uiState.value.draft.text)
        }

    @Test
    fun deleteOffersUndo() =
        runTest(dispatcher) {
            val dictation = FakeDictation().apply { texts += listOf("one", "two") }
            val vm = HomeViewModel(dictation) { now }
            repeat(2) {
                vm.press()
                vm.release(TalkRelease.End)
                advanceUntilIdle()
            }
            vm.selectChunk(0)
            vm.deleteChunk()
            assertEquals("two", vm.uiState.value.draft.text)
            assertEquals(HomeNotice.ChunkDeleted, vm.uiState.value.notice)
            vm.undoDelete()
            assertEquals("one two", vm.uiState.value.draft.text)
        }

    @Test
    fun clearEmptiesTheDraft() =
        runTest(dispatcher) {
            val dictation = FakeDictation().apply { texts += "one" }
            val vm = HomeViewModel(dictation) { now }
            vm.press()
            vm.release(TalkRelease.End)
            advanceUntilIdle()
            vm.clearDraft()
            assertTrue(vm.uiState.value.draft.isEmpty)
        }

    @Test
    fun aNewDayShowsTheNoticeOnceAndTheEmptyCard() =
        runTest(dispatcher) {
            val vm = HomeViewModel(FakeDictation(), makeSession = { TodaySessionDemo.NewDay.start(it) }) { now }
            advanceTimeBy(2_000)
            runCurrent()
            val today = vm.uiState.value.today
            assertTrue(today.setup is SessionSetup.Ready)
            assertTrue(today.summary.summary is StatusSummary.Empty)
            assertEquals(HomeNotice.NewDay, vm.uiState.value.notice)
            vm.noticeShown()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(null, vm.uiState.value.notice)
            // The sample stream heartbeats forever.
            vm.viewModelScope.cancel()
        }

    @Test
    fun aFailedSetupShowsTryAgain() =
        runTest(dispatcher) {
            val vm = HomeViewModel(FakeDictation(), makeSession = { TodaySessionDemo.Failed.start(it) }) { now }
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(SessionSetup.Failed(retrying = false), vm.uiState.value.today.setup)
            vm.retrySetup()
            runCurrent()
            assertEquals(SessionSetup.Failed(retrying = true), vm.uiState.value.today.setup)
            vm.viewModelScope.cancel()
        }
}
