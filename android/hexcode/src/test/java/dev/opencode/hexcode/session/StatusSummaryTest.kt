package dev.opencode.hexcode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                now = "Adding a refresh on 401 to the API client.",
                next = "Rerun the auth tests",
                steps =
                    listOf(
                        SummaryStep("Find where tokens are stored", done = true),
                        SummaryStep("Add the refresh call", done = true),
                        SummaryStep("Retry the failed request", done = false),
                        SummaryStep("Run the auth tests", done = false),
                    ),
            ),
            StatusSummary.parse(full),
        )
    }

    @Test
    fun noStepsIsACardWithoutSteps() {
        val card = StatusSummary.parse("## Token refresh\nNow: Reading the API client.\nNext: Add the refresh call") as StatusSummary.Card
        assertEquals(emptyList<SummaryStep>(), card.steps)
        assertEquals("Add the refresh call", card.next)
    }

    @Test
    fun noNextIsACardWithoutNext() {
        val card = StatusSummary.parse("## Token refresh\nNow: Reading the API client.\n- [ ] Add the refresh call") as StatusSummary.Card
        assertEquals(null, card.next)
        assertEquals(listOf(SummaryStep("Add the refresh call", done = false)), card.steps)
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
    fun noNowLineIsAFallback() {
        val reply = "## Token refresh\nI'm adding a refresh on 401.\n- [x] Find where tokens are stored"
        assertEquals(
            StatusSummary.Fallback("## Token refresh I'm adding a refresh on 401. - [x] Find where tokens are stored"),
            StatusSummary.parse(reply),
        )
    }

    @Test
    fun noTitleIsAFallback() {
        assertTrue(StatusSummary.parse("Now: Reading the API client.\nNext: Add the refresh call") is StatusSummary.Fallback)
    }

    @Test
    fun anEmptyTitleOrNowLineCountsAsMissing() {
        assertTrue(StatusSummary.parse("## \nNow: Reading the API client.") is StatusSummary.Fallback)
        assertTrue(StatusSummary.parse("## Token refresh\nNow:") is StatusSummary.Fallback)
    }

    @Test
    fun theFallbackIsPlainTextOfAtMost300Characters() {
        val long = "```\n" + "word ".repeat(100) + "\n```"
        val fallback = StatusSummary.parse(long) as StatusSummary.Fallback
        assertTrue(fallback.text.length <= 300)
        assertTrue(fallback.text.endsWith("word…"))
        assertTrue(!fallback.text.contains("```"))
    }

    @Test
    fun theFirstOfEachLineWinsAndStepsKeepTheirOrder() {
        val card =
            StatusSummary.parse(
                "## First\n## Second\nNow: One.\nNow: Two.\nNext: A\nNext: B\n- [ ] 1\n- [X] 2\n- [x] 3",
            ) as StatusSummary.Card
        assertEquals("First", card.title)
        assertEquals("One.", card.now)
        assertEquals("A", card.next)
        assertEquals(listOf(false, true, true), card.steps.map { it.done })
        assertEquals(listOf("1", "2", "3"), card.steps.map { it.text })
    }

    @Test
    fun atMostEightStepsAreKept() {
        val steps = (1..10).joinToString("\n") { "- [ ] Step $it" }
        val card = StatusSummary.parse("## Many\nNow: Working.\n$steps") as StatusSummary.Card
        assertEquals((1..8).map { "Step $it" }, card.steps.map { it.text })
    }

    @Test
    fun theCardIsClippedToTitle40Now90Next60() {
        val title = "A title that runs on far longer than forty characters allow"
        val now = "Now I am doing something that takes a very long sentence to describe, longer than the ninety characters"
        val next = "Then something else that also takes more than sixty characters to say"
        val card = StatusSummary.parse("## $title\nNow: $now\nNext: $next") as StatusSummary.Card
        assertEquals("A title that runs on far longer than…", card.title)
        assertEquals("Now I am doing something that takes a very long sentence to describe, longer than the…", card.now)
        assertEquals("Then something else that also takes more than sixty…", card.next)
        assertTrue(card.title.length <= 40 && card.now.length <= 90 && card.next!!.length <= 60)
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
    fun stepsAreClippedTo40() {
        val long = "Rewrite every test that leans on the old session helper"
        val card = StatusSummary.parse("## Task\nNow: Working.\n- [ ] $long") as StatusSummary.Card
        assertEquals("Rewrite every test that leans on the…", card.steps.single().text)
        assertTrue(
            card.steps
                .single()
                .text.length <= 40,
        )
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
