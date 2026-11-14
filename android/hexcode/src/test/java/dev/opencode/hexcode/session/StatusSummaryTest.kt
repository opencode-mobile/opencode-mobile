package dev.opencode.hexcode.session

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** app-plan.md, Testing, M2 cases: "Summary: the full shape; no steps; no Next:; code fences; text before the title; no Now: → fallback". */
class StatusSummaryTest {
    private val full =
        """
        ## Token refresh
        Now: Adding a refresh on 401 to the API client.
        Next: Rerun the auth tests
        - [x] Find where tokens are stored
        - [x] Add the refresh call
        - [ ] Retry the failed request
        - [ ] Run the auth tests
        """.trimIndent()

    @Test
    fun theFullShapeIsACard() {
        assertEquals(
            StatusSummary.Card(
                title = "Token refresh",
                now = listOf(NowLine("Adding a refresh on 401 to the API client.")),
                next = "Rerun the auth tests",
            ),
            StatusSummary.parse(full),
        )
    }

    @Test
    fun stepLinesAreIgnored() {
        // The summary no longer asks for steps; a model that still writes them changes nothing.
        assertEquals(
            StatusSummary.parse("## Token refresh\nNow: Adding a refresh on 401 to the API client.\nNext: Rerun the auth tests"),
            StatusSummary.parse(full),
        )
    }

    @Test
    fun noNextIsACardWithoutNext() {
        val card = StatusSummary.parse("## Token refresh\nNow: Reading the API client.\n- [ ] Add the refresh call") as StatusSummary.Card
        assertEquals(null, card.next)
    }

    @Test
    fun codeFencesAndBlankLinesAreIgnored() {
        assertEquals(StatusSummary.parse(full), StatusSummary.parse("```markdown\n\n${full.replace("\n", "\n\n")}\n```\n"))
    }

    @Test
    fun textBeforeTheTitleIsIgnored() {
        assertEquals(StatusSummary.parse(full), StatusSummary.parse("Here is the summary you asked for:\n\n$full\nHope that helps."))
    }

    @Test
    fun noNowLineReadsTheLinesAfterTheTitle() {
        val reply = "## Token refresh\nI'm adding a refresh on 401.\n- [x] Find where tokens are stored"
        assertEquals(
            StatusSummary.Card(
                "Token refresh",
                listOf(NowLine("I'm adding a refresh on 401."), NowLine("[x] Find where tokens are stored", bullet = true)),
                null,
            ),
            StatusSummary.parse(reply),
        )
    }

    @Test
    fun noTitleIsACardWithoutATitle() {
        val card =
            StatusSummary.parse(
                "Now: Reading the API client in full, file by file, and noting every call.\nNext: Add the refresh call",
            ) as StatusSummary.Card
        assertEquals("", card.title)
        assertEquals("Add the refresh call", card.next)
    }

    @Test
    fun anEmptyHeadingOrNowSaysNothing() {
        assertEquals("", (StatusSummary.parse("## \nNow: Reading the API client.") as StatusSummary.Card).title)
        assertEquals(emptyList<NowLine>(), (StatusSummary.parse("## Token refresh\nNow:") as StatusSummary.Card).now)
    }

    @Test
    fun aReplyWithNoShapeIsReadAsNowAndNotCut() {
        val long = "```\n" + "word ".repeat(100) + "\n```"
        val fallback = StatusSummary.parse(long) as StatusSummary.Fallback
        assertEquals(listOf(NowLine("word ".repeat(100).trim())), fallback.now)
    }

    @Test
    fun theFirstTitleAndNextWinAndLaterNowLinesAreKept() {
        val card =
            StatusSummary.parse(
                "## First\n## Second\nNow: One.\nNow: Two.\nNext: A\nNext: B",
            ) as StatusSummary.Card
        assertEquals("First", card.title)
        assertEquals(listOf("## Second", "One.", "Two."), card.now.map { it.text })
        assertEquals("A", card.next)
    }

    @Test
    fun nothingIsCutByCharacters() {
        val title = "A title that runs on far longer than forty characters allow"
        val sentence = "Now I am doing something that takes a very long sentence to describe, longer than ninety characters. "
        val now = sentence.repeat(4).trim()
        val next = "Then something else that also takes more than sixty characters to say"
        val card = StatusSummary.parse("## $title\nNow: $now\nNext: $next") as StatusSummary.Card
        assertEquals(title, card.title)
        assertEquals(now, card.nowText)
        assertEquals(next, card.next)
    }

    @Test
    fun linkLinesDoNotMakeAFallback() {
        val reply =
            "## Login rewrite\nNow: Three apps share one API client.\nNext: Run the tests.\n- [x] Read the code\n" +
                "Link: Dev server | http://localhost:5173/login\nLink: PR | https://github.com/o/r/pull/1"
        val card = StatusSummary.parse(reply) as StatusSummary.Card
        assertEquals("Three apps share one API client.", card.nowText)
        assertEquals("Run the tests.", card.next)
    }

    @Test
    fun clipMatchesThePrototype() {
        assertEquals("short", StatusSummary.clip("short", 10))
        assertEquals("exactly10!", StatusSummary.clip("exactly10!", 10))
        // At the last space past half way, without the trailing comma.
        assertEquals("one two, three…", StatusSummary.clip("one two, three, four five", 17))
        // No space past half way: a hard cut.
        assertEquals("abcdefghi…", StatusSummary.clip("abcdefghijklmnop", 10))
    }

    @Test
    fun theEmptyCardIsTitledWithTheLongDate() {
        assertEquals(StatusSummary.Empty("Thursday 5 November"), StatusSummary.empty(LocalDate.of(2026, 11, 5)))
        assertEquals("Tuesday 3 November", StatusSummary.longDate(LocalDate.of(2026, 11, 3)))
    }

    @Test
    fun clippingCountsWhatTheUserSeesAsOneCharacter() {
        // "é" written as e + a combining accent is one character; so is an emoji outside the basic plane.
        val accented = "e\u0301".repeat(12)
        assertEquals(accented, StatusSummary.clip(accented, 12))
        assertEquals("e\u0301".repeat(9) + "…", StatusSummary.clip(accented, 10))
        val rockets = "\uD83D\uDE80".repeat(5)
        assertEquals(rockets, StatusSummary.clip(rockets, 5))
        assertEquals("\uD83D\uDE80".repeat(3) + "…", StatusSummary.clip(rockets, 4))
    }

    @Test
    fun theLongDateIsEnglishDayBeforeMonthWhateverTheRegion() {
        val before = Locale.getDefault()
        try {
            for (region in listOf(Locale.US, Locale.GERMANY, Locale.JAPAN, Locale("ar", "EG"), Locale.FRANCE)) {
                Locale.setDefault(region)
                assertEquals("Thursday 5 November", StatusSummary.longDate(LocalDate.of(2026, 11, 5)))
            }
        } finally {
            Locale.setDefault(before)
        }
    }
}
