package dev.opencode.mobile.domain.textmate

import dev.opencode.mobile.markdown.MarkdownDiagnosticKind
import dev.opencode.mobile.markdown.TextMateParseObservation
import dev.opencode.mobile.markdown.TextMateParseOutcome
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextMateLogbookTest {
    @Test
    fun persistsRecoversCorruptTailAndClears() =
        runTest {
            val directory = Files.createTempDirectory("textmate-logbook-test").toFile()
            val file = TextMateLogbook.fileIn(directory)
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val store =
                TextMateLogbook(
                    file = file,
                    dispatcher = dispatcher,
                    clockEpochMilliseconds = { 42L },
                )

            store.submit(listOf(observation(language = "kotlin")))
            val first = store.snapshot()
            assertEquals(1, first.entries.size)
            assertEquals(42L, first.entries.single().timestampEpochMilliseconds)
            assertEquals("kotlin", first.entries.single().observation.canonicalLanguage)

            file.appendText("{corrupt-tail")
            val reopened = TextMateLogbook(file = file, dispatcher = dispatcher)
            assertEquals(1, reopened.snapshot().entries.size)

            reopened.clear()
            assertTrue(reopened.snapshot().entries.isEmpty())
            assertFalse(file.exists())
        }

    @Test
    fun compactsToNewestFiveHundredEntriesWithoutPersistingContent() =
        runTest {
            val directory = Files.createTempDirectory("textmate-logbook-retention").toFile()
            val file = TextMateLogbook.fileIn(directory)
            val store =
                TextMateLogbook(
                    file = file,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            val observations =
                (0 until 560).map { index ->
                    observation(language = "language-$index")
                }

            store.submit(observations)
            val snapshot = store.snapshot()

            assertEquals(TextMateLogbook.MAX_PERSISTED_ENTRIES, snapshot.entries.size)
            assertEquals("language-559", snapshot.entries.first().observation.canonicalLanguage)
            val persisted = file.readText()
            assertFalse(persisted.contains("secret source"))
            assertFalse(persisted.contains("session-id"))
            assertFalse(persisted.contains("diagnostic message"))
        }

    private fun observation(language: String): TextMateParseObservation =
        TextMateParseObservation(
            requestedLanguage = language,
            canonicalLanguage = language,
            outcome = TextMateParseOutcome.Highlighted,
            messageCharacters = 120,
            fenceCharacters = 80,
            fenceLines = 4,
            fenceIndex = 1,
            fenceCount = 1,
            spanCount = 8,
            backend = "joni",
            totalNanoseconds = 1_000_000,
            stages = emptyList(),
            diagnosticKinds = listOf(MarkdownDiagnosticKind.HighlighterFallback),
        )
}
