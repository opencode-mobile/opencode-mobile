package dev.opencode.hexcode.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** app-plan.md, Dictation › The speech model: recording never waits for the model; only turning a take into text does. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechWaitTest {
    private val dispatcher = StandardTestDispatcher()

    /** An engine whose readiness the test sets; each take's samples carry its number, and its text is "take N". */
    private class Engine : Dictation {
        override val level = 0f
        override val speech = MutableStateFlow<SpeechStatus>(SpeechStatus.Loading())
        var take = 0
        var recording = false
        val transcribed = mutableListOf<Int>()
        var failing = false
        var reloads = 0
        var nextReload: SpeechStatus = SpeechStatus.Ready
        var gate: CompletableDeferred<Unit>? = null

        override fun start(onFailed: (Throwable) -> Unit) {
            recording = true
        }

        override suspend fun end(): FloatArray? {
            if (!recording) return null
            recording = false
            take += 1
            return FloatArray(16_000) { take.toFloat() }
        }

        override fun drop() {
            recording = false
        }

        override suspend fun transcribe(samples: FloatArray): String {
            gate?.await()
            if (failing) error("The engine crashed.")
            val number = samples.first().toInt()
            transcribed += number
            return "take $number"
        }

        override fun reload() {
            reloads += 1
            speech.value = nextReload
        }

        var loadsNow = 0

        override fun loadNow() {
            loadsNow += 1
        }
    }

    private val store = MemoryTakeStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun HomeViewModel.take() {
        press()
        release(TalkRelease.End)
    }

    @Test
    fun aTalkPressLoadsTheModelAtOnceWithoutWaitingForTheDelayAfterStartUp() =
        runTest(dispatcher) {
            val engine = Engine()
            val vm = HomeViewModel(engine, takes = store) { 0 }
            assertEquals(0, engine.loadsNow)
            vm.press()
            assertEquals(1, engine.loadsNow)
            // Recording didn't wait for it.
            assertTrue(engine.recording)
        }

    @Test
    fun aTakeWhileTheModelLoadsWaitsThenGetsItsText() =
        runTest(dispatcher) {
            val engine = Engine()
            val vm = HomeViewModel(engine, takes = store) { 0 }
            vm.take()
            advanceUntilIdle()
            val draft = vm.uiState.value.draft
            assertEquals(ChunkState.Waiting, draft.chunks.single().state)
            assertEquals(DraftLabel.SpeechLoading, DraftLabel.of(draft, vm.uiState.value.speech))
            // Its audio is kept until it has text.
            assertEquals(setOf(1L), store.ids)
            engine.speech.value = SpeechStatus.Ready
            advanceUntilIdle()
            assertEquals("take 1", vm.uiState.value.draft.text)
            assertTrue(store.ids.isEmpty())
        }

    @Test
    fun threeWaitingTakesComeOutInTheOrderTheyWereRecorded() =
        runTest(dispatcher) {
            val engine = Engine()
            val vm = HomeViewModel(engine, takes = store) { 0 }
            repeat(3) {
                vm.take()
                advanceUntilIdle()
            }
            assertEquals(3, vm.uiState.value.draft.waitingIds.size)
            engine.speech.value = SpeechStatus.Ready
            advanceUntilIdle()
            assertEquals(listOf(1, 2, 3), engine.transcribed)
            assertEquals("take 1 take 2 take 3", vm.uiState.value.draft.text)
        }

    @Test
    fun aModelFailureThenTryAgainThenText() =
        runTest(dispatcher) {
            val engine = Engine()
            val vm = HomeViewModel(engine, takes = store) { 0 }
            vm.take()
            advanceUntilIdle()
            engine.speech.value = SpeechStatus.Failed
            advanceUntilIdle()
            val failed = vm.uiState.value.draft
            assertEquals(ChunkState.Failed(retry = true), failed.chunks.single().state)
            assertEquals(DraftLabel.SpeechFailed, DraftLabel.of(failed, SpeechStatus.Failed))
            assertTrue(failed.canRetry)
            vm.retryTakes()
            advanceUntilIdle()
            assertEquals(1, engine.reloads)
            assertEquals("take 1", vm.uiState.value.draft.text)
        }

    @Test
    fun aFailedWaitingTakeCanStillBeDeletedOrReRecorded() =
        runTest(dispatcher) {
            val engine = Engine()
            val vm = HomeViewModel(engine, takes = store) { 0 }
            repeat(2) {
                vm.take()
                advanceUntilIdle()
            }
            engine.speech.value = SpeechStatus.Failed
            advanceUntilIdle()
            vm.selectChunk(0)
            vm.deleteChunk()
            assertEquals(1, vm.uiState.value.draft.chunks.size)
            engine.speech.value = SpeechStatus.Ready
            advanceUntilIdle()
            vm.selectChunk(0)
            vm.rerecord()
            vm.tapLocked()
            advanceUntilIdle()
            assertEquals("take 3", vm.uiState.value.draft.text)
        }

    @Test
    fun sendWaitsWhileATakeWaits() =
        runTest(dispatcher) {
            val engine = Engine()
            val vm = HomeViewModel(engine, takes = store) { 0 }
            vm.take()
            advanceUntilIdle()
            val draft = vm.uiState.value.draft
            // Send, Ask and Set as goal wait for every chunk; Clear can still empty the draft.
            assertFalse(draft.isSettled)
            assertTrue(draft.canClear)
            engine.speech.value = SpeechStatus.Ready
            advanceUntilIdle()
            assertTrue(vm.uiState.value.draft.isSettled)
        }

    @Test
    fun anEngineFailureOnAReadyModelKeepsTheAudioForTryAgain() =
        runTest(dispatcher) {
            val engine = Engine().apply { speech.value = SpeechStatus.Ready }
            engine.failing = true
            val vm = HomeViewModel(engine, takes = store) { 0 }
            vm.take()
            advanceUntilIdle()
            assertEquals(
                ChunkState.Failed(retry = true),
                vm.uiState.value.draft.chunks
                    .single()
                    .state,
            )
            // The model is fine, so the label doesn't blame it.
            assertEquals(DraftLabel.Summary, DraftLabel.of(vm.uiState.value.draft, SpeechStatus.Ready))
            engine.failing = false
            vm.retryTakes()
            advanceUntilIdle()
            assertEquals(0, engine.reloads)
            assertEquals("take 1", vm.uiState.value.draft.text)
        }

    @Test
    fun theLabelRules() {
        val waiting = Draft().startTake().endTake(1_000).let { it.waiting(it.chunks.single().id) }
        assertEquals(DraftLabel.SpeechLoading, DraftLabel.of(waiting, SpeechStatus.Loading()))
        assertEquals(DraftLabel.SpeechDownloading(45), DraftLabel.of(waiting, SpeechStatus.Loading(downloaded = 0.456)))
        // Recording comes first, then transcribing.
        assertEquals(DraftLabel.Recording, DraftLabel.of(waiting.startTake(), SpeechStatus.Loading()))
        val transcribing = Draft().startTake().endTake(1_000)
        assertEquals(DraftLabel.Transcribing, DraftLabel.of(transcribing, SpeechStatus.Ready))
        // With no chunk waiting, nothing about the model shows.
        val settled = transcribing.transcribed(transcribing.chunks.single().id, "Hello")
        assertEquals(DraftLabel.Summary, DraftLabel.of(settled, SpeechStatus.Loading(downloaded = 0.2)))
        assertEquals(DraftLabel.Summary, DraftLabel.of(Draft(), SpeechStatus.Failed))
        assertEquals(DraftLabel.SpeechFailed, DraftLabel.of(waiting.waitingFailed(), SpeechStatus.Failed))
    }
}
