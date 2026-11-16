package dev.opencode.hexcode.home

import dev.opencode.voiceengine.VoiceEngine
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

        var triedNow = 0

        override fun tryNow() {
            triedNow += 1
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
        val download = SpeechDownload(done = 120_400_000, total = 640_000_000)
        assertEquals(DraftLabel.SpeechDownloading(120, 640), DraftLabel.of(waiting, SpeechStatus.Loading(download)))
        // Time left once the speed is known: whole minutes rounded up, and "under a minute" below one.
        assertEquals(
            DraftLabel.SpeechDownloading(120, 640, minutesLeft = 3),
            DraftLabel.of(waiting, SpeechStatus.Loading(download.copy(secondsLeft = 121))),
        )
        assertEquals(
            DraftLabel.SpeechDownloading(120, 640, minutesLeft = 0),
            DraftLabel.of(waiting, SpeechStatus.Loading(download.copy(secondsLeft = 59))),
        )
        // Recording comes first, then transcribing.
        assertEquals(DraftLabel.Recording, DraftLabel.of(waiting.startTake(), SpeechStatus.Loading()))
        val transcribing = Draft().startTake().endTake(1_000)
        assertEquals(DraftLabel.Transcribing, DraftLabel.of(transcribing, SpeechStatus.Ready))
        // With no chunk waiting, nothing about the model shows.
        val settled = transcribing.transcribed(transcribing.chunks.single().id, "Hello")
        assertEquals(DraftLabel.Summary, DraftLabel.of(settled, SpeechStatus.Loading(SpeechDownload(1, 10))))
        assertEquals(DraftLabel.Summary, DraftLabel.of(Draft(), SpeechStatus.Failed))
        assertEquals(DraftLabel.SpeechFailed, DraftLabel.of(waiting.waitingFailed(), SpeechStatus.Failed))
    }

    /** app-plan.md, Speech line and dial › Draft label: the shorter words while a chunk waits. */
    @Test
    fun theLabelWhileTheModelGetsReady() {
        val waiting = Draft().startTake().endTake(1_000).let { it.waiting(it.chunks.single().id) }
        val dropped = SpeechDownload(done = 120_000_000, total = 640_000_000, retryIn = 4)
        assertEquals(DraftLabel.SpeechRetrying(4), DraftLabel.of(waiting, SpeechStatus.Loading(dropped)))
        // "trying again…" while the attempt is made.
        assertEquals(DraftLabel.SpeechRetrying(0), DraftLabel.of(waiting, SpeechStatus.Loading(dropped.copy(retryIn = 0))))
        // Loading and warming up read the same.
        assertEquals(DraftLabel.SpeechLoading, DraftLabel.of(waiting, SpeechStatus.Loading(stage = SpeechStage.WarmUp)))
        assertEquals(
            DraftLabel.SpeechNotOnPhone,
            DraftLabel.of(waiting, SpeechStatus.Loading(stage = SpeechStage.NotOnPhone)),
        )
        // Each of them carries the dial and opens the sheet; the summary doesn't.
        assertTrue(DraftLabel.SpeechRetrying(4).isSpeech && DraftLabel.SpeechFailed.isSpeech)
        assertFalse(DraftLabel.Summary.isSpeech || DraftLabel.Transcribing.isSpeech)
    }

    /** The engine's state as dictation sees it: not on the phone, downloading, retrying, loading, warming up. */
    @Test
    fun theEngineStatesAsDictationSeesThem() {
        assertEquals(
            SpeechStatus.Loading(stage = SpeechStage.NotOnPhone),
            speechStatus(VoiceEngine.ModelState.NotLoaded) { false },
        )
        assertEquals(SpeechStatus.Loading(), speechStatus(VoiceEngine.ModelState.NotLoaded) { true })
        val downloading =
            VoiceEngine.ModelState.Preparing(0.2, "", done = 120, total = 600, bytesPerSecond = 8_400_000, retryIn = 2)
        assertEquals(
            SpeechStatus.Loading(SpeechDownload(120, 600, bytesPerSecond = 8_400_000, retryIn = 2)),
            speechStatus(downloading),
        )
        val warming = VoiceEngine.ModelState.Preparing(1.0, "", stage = VoiceEngine.ModelState.Stage.WarmUp)
        assertEquals(SpeechStatus.Loading(stage = SpeechStage.WarmUp), speechStatus(warming))
    }

    @Test
    fun theSpeechLineShowsWhileTheModelGetsReadyThenSaysReadyThenGoesForGood() =
        runTest(dispatcher) {
            val engine = Engine()
            val memory = SpeechLineMemory()
            val vm = HomeViewModel(engine, takes = store, speechLine = memory) { 0 }
            advanceUntilIdle()
            assertEquals(SpeechLineShow.GettingReady, vm.uiState.value.speechLine)
            engine.speech.value = SpeechStatus.Ready
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeechLineShow.Ready, vm.uiState.value.speechLine)
            // A home made while it says so (the last one cleared mid-way) doesn't say it again.
            val during =
                HomeViewModel(Engine().apply { speech.value = SpeechStatus.Ready }, takes = MemoryTakeStore(), speechLine = memory) { 0 }
            dispatcher.scheduler.runCurrent()
            assertEquals(SpeechLineShow.Hidden, during.uiState.value.speechLine)
            dispatcher.scheduler.advanceTimeBy(SpeechLineMemory.READY_MS + 1)
            assertEquals(SpeechLineShow.Hidden, vm.uiState.value.speechLine)
            // It doesn't come back in this process, on this home or the next.
            engine.speech.value = SpeechStatus.Failed
            advanceUntilIdle()
            assertEquals(SpeechLineShow.Hidden, vm.uiState.value.speechLine)
            val next = HomeViewModel(Engine(), takes = MemoryTakeStore(), speechLine = memory) { 0 }
            advanceUntilIdle()
            assertEquals(SpeechLineShow.Hidden, next.uiState.value.speechLine)
        }

    @Test
    fun aModelReadyFromTheStartNeverShowsTheSpeechLine() =
        runTest(dispatcher) {
            val engine = Engine().apply { speech.value = SpeechStatus.Ready }
            val vm = HomeViewModel(engine, takes = store, speechLine = SpeechLineMemory()) { 0 }
            advanceUntilIdle()
            assertEquals(SpeechLineShow.Hidden, vm.uiState.value.speechLine)
        }

    @Test
    fun theSheetOpensAndItsTryNowAndTryAgainReachTheEngine() =
        runTest(dispatcher) {
            val engine = Engine().apply { speech.value = SpeechStatus.Failed }
            val vm = HomeViewModel(engine, takes = store, speechLine = SpeechLineMemory()) { 0 }
            advanceUntilIdle()
            vm.openSpeechSheet()
            assertTrue(vm.uiState.value.speechSheet)
            vm.speechTryNow()
            assertEquals(1, engine.triedNow)
            // Try again loads the model again, with no take waiting too.
            vm.speechTryAgain()
            advanceUntilIdle()
            assertEquals(1, engine.reloads)
            assertEquals(SpeechStatus.Ready, vm.uiState.value.speech)
            vm.closeSpeechSheet()
            assertFalse(vm.uiState.value.speechSheet)
        }
}
