package dev.opencode.hexcode.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * app-plan.md, Dictation › Locked takes and States per chunk: a locked take is transcribed in parts at the speaker's
 * pauses while it records, and stays one chunk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockedTakeTest {
    private val dispatcher = StandardTestDispatcher()

    /**
     * Dictation whose pauses the test makes: [cut] sends a part whose samples carry its number, and its text is
     * "Part N."; a take turned into text whole has samples of 0 and reads "The whole take.".
     */
    private class Cutting : Dictation {
        override val level = 0f
        override val speech = MutableStateFlow<SpeechStatus>(SpeechStatus.Ready)

        override fun reload() = Unit

        var pausesReady = true
        var recording = false
        var parts: SendChannel<TakePart>? = null
        var splits = 0

        /** Sent when the take ends: the audio after the last cut, when there was speech in it. */
        var rest: Int? = null

        /** Closes the parts with an error at the end, as a pause detector that broke would. */
        var breakAtEnd = false
        val transcribed = mutableListOf<Int>()
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var failOn: Int? = null

        override fun start(onFailed: (Throwable) -> Unit) {
            recording = true
        }

        override fun split(parts: SendChannel<TakePart>): Boolean {
            if (!pausesReady) return false
            splits += 1
            this.parts = parts
            return true
        }

        fun cut(number: Int) {
            parts!!.trySend(part(number, last = false))
        }

        private fun part(
            number: Int,
            last: Boolean,
        ) = TakePart(number, 0, FloatArray(16_000) { number.toFloat() }, last, 0, 0)

        override suspend fun end(): FloatArray? {
            if (!recording) return null
            recording = false
            parts?.let { channel ->
                rest?.let { channel.trySend(part(it, last = true)) }
                if (breakAtEnd) channel.close(IllegalStateException("The detector broke.")) else channel.close()
            }
            parts = null
            return FloatArray(16_000 * 10)
        }

        override fun drop() {
            recording = false
            parts = null
        }

        override suspend fun transcribe(samples: FloatArray): String {
            val number = samples.first().toInt()
            gates[number]?.await()
            transcribed += number
            if (failOn == number) error("The engine crashed.")
            return if (number == 0) "The whole take." else "Part $number."
        }
    }

    private val store = MemoryTakeStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun HomeViewModel.lock() {
        press()
        release(TalkRelease.Tap)
    }

    private val HomeViewModel.draft get() = uiState.value.draft

    @Test
    fun theTextGrowsWhileTheTakeIsStillListening() =
        runTest(dispatcher) {
            val dictation = Cutting()
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            assertEquals(1, dictation.splits)
            dictation.cut(1)
            advanceUntilIdle()
            assertEquals("Part 1.", vm.draft.live?.soFar)
            assertEquals(ChunkState.Listening, vm.draft.live?.state)
            dictation.cut(2)
            advanceUntilIdle()
            assertEquals("Part 1. Part 2.", vm.draft.live?.soFar)
            // Still one take, recording, and nothing to send yet.
            assertTrue(vm.draft.chunks.isEmpty())
            assertFalse(vm.uiState.value.canSend)
        }

    @Test
    fun afterReleaseOnlyTheLastPartIsTranscribedThenTheChunkHasItsText() =
        runTest(dispatcher) {
            val dictation = Cutting().apply { rest = 3 }
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            dictation.cut(1)
            dictation.cut(2)
            advanceUntilIdle()
            dictation.gates[3] = CompletableDeferred()
            vm.tapLocked()
            advanceUntilIdle()
            // The last part is on its way: the chunk is Transcribing, showing what it has so far, and Send waits.
            val waiting = vm.draft.chunks.single()
            assertEquals(ChunkState.Transcribing, waiting.state)
            assertEquals("Part 1. Part 2.", waiting.soFar)
            assertFalse(vm.uiState.value.canSend)
            dictation.gates.getValue(3).complete(Unit)
            advanceUntilIdle()
            val done = vm.draft.chunks.single()
            assertEquals(ChunkState.Text("Part 1. Part 2. Part 3."), done.state)
            assertEquals("", done.soFar)
            // Each part once, in order; the whole take never.
            assertEquals(listOf(1, 2, 3), dictation.transcribed)
            assertTrue(vm.uiState.value.canSend)
        }

    @Test
    fun partsAreTranscribedOneAtATimeInOrder() =
        runTest(dispatcher) {
            val dictation = Cutting()
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            dictation.gates[1] = CompletableDeferred()
            dictation.cut(1)
            dictation.cut(2)
            dictation.cut(3)
            advanceUntilIdle()
            // Part 1 holds the engine: the others wait behind it.
            assertTrue(dictation.transcribed.isEmpty())
            assertEquals("", vm.draft.live?.soFar)
            dictation.gates.getValue(1).complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(1, 2, 3), dictation.transcribed)
            assertEquals("Part 1. Part 2. Part 3.", vm.draft.live?.soFar)
        }

    @Test
    fun releaseWithNoSpeechSinceTheLastCutNeedsNoMoreTranscribing() =
        runTest(dispatcher) {
            val dictation = Cutting().apply { rest = null }
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            dictation.cut(1)
            advanceUntilIdle()
            vm.tapLocked()
            advanceUntilIdle()
            assertEquals("Part 1.", vm.draft.text)
            assertEquals(listOf(1), dictation.transcribed)
        }

    @Test
    fun aLockedTakeWithNoSpeechLeavesTheDraft() =
        runTest(dispatcher) {
            val dictation = Cutting()
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            vm.tapLocked()
            advanceUntilIdle()
            assertTrue(vm.draft.chunks.isEmpty())
            assertTrue(dictation.transcribed.isEmpty())
        }

    @Test
    fun droppingDuringAPartStopsItAndDiscardsTheText() =
        runTest(dispatcher) {
            val dictation = Cutting()
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            dictation.cut(1)
            advanceUntilIdle()
            dictation.gates[2] = CompletableDeferred()
            dictation.cut(2)
            dictation.cut(3)
            advanceUntilIdle()
            vm.dropTakeNow()
            // The engine would have finished part 2 now; its text, and part 3, go with the take.
            dictation.gates.getValue(2).complete(Unit)
            advanceUntilIdle()
            assertTrue(vm.draft.isEmpty)
            assertEquals(HomeNotice.TakeDropped, vm.uiState.value.notice)
            assertEquals(listOf(1), dictation.transcribed)
            // The next take starts clean.
            dictation.rest = 1
            vm.lock()
            vm.tapLocked()
            advanceUntilIdle()
            assertEquals("Part 1.", vm.draft.text)
        }

    @Test
    fun withoutThePauseDetectorALockedTakeIsTranscribedWholeAfterRelease() =
        runTest(dispatcher) {
            val dictation = Cutting().apply { pausesReady = false }
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            assertNull(dictation.parts)
            vm.tapLocked()
            advanceUntilIdle()
            assertEquals("The whole take.", vm.draft.text)
            assertEquals(listOf(0), dictation.transcribed)
        }

    @Test
    fun aHeldTakeIsNotCut() =
        runTest(dispatcher) {
            val dictation = Cutting()
            val vm = HomeViewModel(dictation, takes = store)
            vm.press()
            vm.release(TalkRelease.End)
            advanceUntilIdle()
            assertEquals(0, dictation.splits)
            assertEquals("The whole take.", vm.draft.text)
        }

    @Test
    fun aTakeLockedBeforeTheModelIsReadyWaitsWhole() =
        runTest(dispatcher) {
            val dictation = Cutting().apply { speech.value = SpeechStatus.Loading() }
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            assertEquals(0, dictation.splits)
            vm.tapLocked()
            advanceUntilIdle()
            assertEquals(
                ChunkState.Waiting,
                vm.draft.chunks
                    .single()
                    .state,
            )
        }

    @Test
    fun aFailedPartKeepsTheWholeTakeForTryAgain() =
        runTest(dispatcher) {
            val dictation = Cutting().apply { failOn = 1 }
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            dictation.cut(1)
            dictation.cut(2)
            advanceUntilIdle()
            vm.tapLocked()
            advanceUntilIdle()
            val chunk = vm.draft.chunks.single()
            assertEquals(ChunkState.Failed(retry = true), chunk.state)
            assertEquals("", chunk.soFar)
            // The parts after the failed one weren't tried; the whole take's audio is kept.
            assertEquals(listOf(1), dictation.transcribed)
            assertEquals(16_000 * 10, store.load(chunk.id)?.size)
            vm.retryTakes()
            advanceUntilIdle()
            assertEquals("The whole take.", vm.draft.text)
        }

    @Test
    fun aDetectorThatBreaksLeavesTheTakeToBeTranscribedWhole() =
        runTest(dispatcher) {
            val dictation = Cutting().apply { breakAtEnd = true }
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            dictation.cut(1)
            advanceUntilIdle()
            vm.tapLocked()
            advanceUntilIdle()
            assertEquals("The whole take.", vm.draft.text)
        }

    @Test
    fun reRecordingIsOneLockedTakeThatReplacesTheWholeChunk() =
        runTest(dispatcher) {
            val dictation = Cutting().apply { rest = 2 }
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            dictation.cut(1)
            vm.tapLocked()
            advanceUntilIdle()
            assertEquals("Part 1. Part 2.", vm.draft.text)
            vm.selectChunk(0)
            dictation.rest = 3
            vm.rerecord()
            assertEquals(2, dictation.splits)
            vm.tapLocked()
            advanceUntilIdle()
            assertEquals("Part 3.", vm.draft.text)
            assertEquals(1, vm.draft.chunks.size)
        }

    @Test
    fun deletingALockedTakeDeletesAllOfIt() =
        runTest(dispatcher) {
            val dictation = Cutting().apply { rest = 2 }
            val vm = HomeViewModel(dictation, takes = store)
            vm.lock()
            dictation.cut(1)
            vm.tapLocked()
            advanceUntilIdle()
            vm.selectChunk(0)
            vm.deleteChunk()
            assertTrue(vm.draft.chunks.isEmpty())
            assertNotNull(vm.draft.deleted)
        }

    @Test
    fun theDraftCountsTheTextSoFarAsItGrows() {
        val draft = Draft().startTake().let { it.heard(it.live!!.id, "Rename the login screen.") }
        assertEquals("Rename the login screen.".length, draft.characters)
    }
}
