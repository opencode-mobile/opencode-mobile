package dev.opencode.hexcode.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** app-plan.md, Long drafts: running text as long as it can, stepping down by measuring; rows only after that. */
class DraftShapeTest {
    @Test
    fun moreThanThreeChunksAreRows() {
        assertEquals(DraftShape.Rows, DraftShape.choose(chunks = 4, characters = 20) { true })
    }

    @Test
    fun aShortDraftStartsAtTheDraftSize() {
        assertEquals(DraftShape.Running(DraftSize.Draft), DraftShape.choose(chunks = 1, characters = 150) { true })
    }

    @Test
    fun aLongerDraftStartsAt21() {
        assertEquals(DraftShape.Running(DraftSize.Medium), DraftShape.choose(chunks = 1, characters = 151) { true })
    }

    @Test
    fun itStepsDownUntilItFits() {
        val tried = mutableListOf<DraftSize>()
        val shape =
            DraftShape.choose(chunks = 3, characters = 120) {
                tried += it
                it == DraftSize.Fit
            }
        assertEquals(DraftShape.Running(DraftSize.Fit), shape)
        assertEquals(DraftSize.entries, tried)
    }

    @Test
    fun twoOrMoreChunksThatDontFitAt17AreRows() {
        assertEquals(DraftShape.Rows, DraftShape.choose(chunks = 2, characters = 900) { false })
        assertEquals(DraftShape.Rows, DraftShape.choose(chunks = 3, characters = 900) { false })
    }

    @Test
    fun aSingleChunkNeverBecomesAList() {
        assertEquals(DraftShape.Window, DraftShape.choose(chunks = 1, characters = 2_000) { false })
    }

    @Test
    fun aSingleChunkLongerThanSevenLinesAt21IsTheWindowEvenIfItWouldFitSmaller() {
        assertEquals(DraftShape.Window, DraftShape.choose(chunks = 1, characters = 900, beyondWindow = { true }) { true })
        // Up to seven lines it stays running text.
        assertEquals(
            DraftShape.Running(DraftSize.Medium),
            DraftShape.choose(chunks = 1, characters = 400, beyondWindow = { false }) { true },
        )
        // Two chunks are never a window.
        assertEquals(
            DraftShape.Running(DraftSize.Medium),
            DraftShape.choose(chunks = 2, characters = 900, beyondWindow = { true }) { true },
        )
    }

    @Test
    fun wordsAreCountedBetweenSpaces() {
        assertEquals(0, Words.count("  "))
        assertEquals(6, Words.count("Make the quiz  three\nrounds long"))
    }
}
