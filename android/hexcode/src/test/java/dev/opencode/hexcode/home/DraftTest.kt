package dev.opencode.hexcode.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftTest {
    /** A draft whose chunks have [texts], each transcribed. */
    private fun draftOf(vararg texts: String): Draft =
        texts.fold(Draft()) { draft, text ->
            val started = draft.startTake()
            val id = started.live!!.id
            started.endTake(durationMs = 4_000).transcribed(id, text)
        }

    @Test
    fun aTakeGoesListeningThenTranscribingThenText() {
        val listening = Draft().startTake()
        assertEquals(ChunkState.Listening, listening.shown.single().state)
        assertTrue(listening.isListening)
        assertFalse(listening.isSettled)

        val id = listening.live!!.id
        val transcribing = listening.endTake(durationMs = 3_200)
        assertEquals(ChunkState.Transcribing, transcribing.chunks.single().state)
        assertEquals(3_200, transcribing.durationMs)
        assertFalse(transcribing.isListening)
        assertTrue(transcribing.isTranscribing)
        assertFalse(transcribing.isSettled)

        val done = transcribing.transcribed(id, "  Add a remember-me checkbox ")
        assertEquals(ChunkState.Text("Add a remember-me checkbox"), done.chunks.single().state)
        assertTrue(done.isSettled)
    }

    @Test
    fun releasingNeverSendsTheTakeJoinsTheDraft() {
        val draft = draftOf("First part", "second part")
        assertEquals("First part second part", draft.text)
        assertEquals(2, draft.chunks.size)
    }

    @Test
    fun aTakeWithNoSpeechLeavesTheDraft() {
        val started = draftOf("Keep this").startTake()
        val id = started.live!!.id
        val after = started.endTake(1_000).transcribed(id, "   ")
        assertEquals(listOf("Keep this"), after.chunks.map { it.text })
    }

    @Test
    fun chunksGetTheirTextInAnyOrder() {
        val a = Draft().startTake()
        val first = a.live!!.id
        val b = a.endTake(1_000).startTake()
        val second = b.live!!.id
        val both = b.endTake(1_000)
        val draft = both.transcribed(second, "two").transcribed(first, "one")
        assertEquals("one two", draft.text)
    }

    @Test
    fun droppingATakeKeepsTheRestOfTheDraft() {
        val draft = draftOf("Earlier").startTake().dropTake()
        assertEquals(listOf("Earlier"), draft.chunks.map { it.text })
        assertFalse(draft.isListening)
    }

    @Test
    fun reRecordingReplacesTheChunkWhenTheTakeEnds() {
        val selected = draftOf("one", "two", "three").select(1)
        val recording = selected.startTake()
        assertEquals(1, recording.rerecording)
        assertNull(recording.selected)
        assertEquals(ChunkState.Listening, recording.shown[1].state)
        assertEquals(3, recording.shown.size)

        val id = recording.live!!.id
        val done = recording.endTake(2_000).transcribed(id, "TWO")
        assertEquals(listOf("one", "TWO", "three"), done.chunks.map { it.text })
    }

    @Test
    fun droppingAReRecordKeepsTheOldText() {
        val draft = draftOf("one", "two").select(0).startTake().dropTake()
        assertEquals(listOf("one", "two"), draft.chunks.map { it.text })
        assertNull(draft.rerecording)
    }

    @Test
    fun onlyChunksWithTextCanBeSelected() {
        val waiting = Draft().startTake().endTake(1_000)
        assertSame(waiting, waiting.select(0))
        val draft = draftOf("one")
        assertEquals(0, draft.select(0).selected)
        assertNull(draft.select(0).select(0).selected)
        assertSame(draft.startTake().select(0).selected, null)
    }

    @Test
    fun deleteThenUndoPutsTheChunkBackInPlace() {
        val draft = draftOf("one", "two", "three").select(1).deleteSelected()
        assertEquals(listOf("one", "three"), draft.chunks.map { it.text })
        assertEquals(listOf("one", "two", "three"), draft.undoDelete().chunks.map { it.text })
    }

    @Test
    fun clearEmptiesASettledDraftOnly() {
        assertTrue(draftOf("one", "two").clear().isEmpty)
        val transcribing = draftOf("one").startTake().endTake(1_000)
        assertSame(transcribing, transcribing.clear())
        val listening = draftOf("one").startTake()
        assertSame(listening, listening.clear())
    }

    @Test
    fun moreThanThreeChunksIsALongDraft() {
        assertFalse(draftOf("a", "b", "c").isLong)
        assertTrue(draftOf("a", "b", "c", "d").isLong)
        // The take being recorded counts as a chunk.
        assertTrue(draftOf("a", "b", "c").startTake().isLong)
    }

    @Test
    fun moreThan220CharactersIsALongDraft() {
        assertFalse(draftOf("x".repeat(110), "y".repeat(109)).isLong)
        assertTrue(draftOf("x".repeat(110), "y".repeat(110)).isLong)
    }

    @Test
    fun aSecondTakeCannotStartWhileOneRecords() {
        val recording = Draft().startTake()
        assertSame(recording, recording.startTake())
    }
}
