package dev.opencode.hexcode.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The card's artifact tile: read with each summary refresh and saved with the card (m6-proposal.md §2.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryArtifactTest {
    private val tile = ArtifactTile("art_1", "Login page preview")

    private class Source(
        var read: ArtifactRead,
    ) : SummarySource {
        var reads = 0

        override suspend fun summarise(prompt: String) = "## Login\nNow: Published the preview."

        override suspend fun artifact(): ArtifactRead {
            reads++
            return read
        }
    }

    private class Store : SummaryStore {
        var saved: SavedSummary? = null

        override suspend fun loadSummary() = saved

        override suspend fun saveSummary(saved: SavedSummary) {
            this.saved = saved
        }
    }

    private val date = LocalDate.of(2026, 11, 9)

    @Test
    fun theTileIsReadWithTheSummaryAndSavedWithTheCard() =
        runTest {
            val source = Source(ArtifactRead.Set(tile))
            val store = Store()
            val summary = SummaryRefresher(this, source, store = store, today = { date })
            summary.open("ses_today", date, "/p")
            summary.refresh()
            advanceUntilIdle()
            assertEquals(1, source.reads)
            assertEquals(tile, summary.state.value.artifact)
            assertEquals(tile, store.saved?.artifact)

            // The next launch shows it with the saved card, before any request (Nothing new keeps it).
            val again = SummaryRefresher(this, Source(ArtifactRead.Keep), store = store, today = { date })
            again.preload(date, "/p", "ses_today")
            assertEquals(tile, again.state.value.artifact)
        }

    @Test
    fun aListThatCouldntBeReadKeepsTheTileAndOffDropsIt() =
        runTest {
            val source = Source(ArtifactRead.Set(tile))
            val summary = SummaryRefresher(this, source, today = { date })
            summary.open("ses_today", date, "/p")
            summary.refresh()
            advanceUntilIdle()
            source.read = ArtifactRead.Keep
            summary.refresh()
            advanceUntilIdle()
            assertEquals(tile, summary.state.value.artifact)
            source.read = ArtifactRead.Set(null)
            summary.refresh()
            advanceUntilIdle()
            assertNull(summary.state.value.artifact)
        }

    @Test
    fun aCardSavedBeforeArtifactsStillReads() {
        val old =
            """{"sessionId":"s","summary":{"type":"card","title":"T","now":"N","next":null},"updatedAt":1}"""
        val saved =
            kotlinx.serialization.json
                .Json { ignoreUnknownKeys = true }
                .decodeFromString(SavedSummary.serializer(), old)
        assertNull(saved.artifact)
    }
}
