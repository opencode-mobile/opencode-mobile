package dev.opencode.hexcode.session

import dev.opencode.hexcode.home.NowSize
import dev.opencode.hexcode.session.SummaryRun.Style.Bold
import dev.opencode.hexcode.session.SummaryRun.Style.Code
import dev.opencode.hexcode.session.SummaryRun.Style.Plain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** app-plan.md, Formatting and Links; the prototype's `md`, `plain` and `pullLinks`. */
class SummaryTextTest {
    @Test
    fun backticksAreCode() {
        assertEquals(
            listOf(SummaryRun("Run ", Plain), SummaryRun("bun test", Code), SummaryRun(" now.", Plain)),
            SummaryText.runs("Run `bun test` now."),
        )
    }

    @Test
    fun doubleStarsAreBold() {
        assertEquals(
            listOf(SummaryRun("It's ", Plain), SummaryRun("done", Bold), SummaryRun(".", Plain)),
            SummaryText.runs("It's **done**."),
        )
    }

    @Test
    fun insideCodeNothingElseCounts() {
        assertEquals(listOf(SummaryRun("**x** [a](b)", Code)), SummaryText.runs("`**x** [a](b)`"))
    }

    @Test
    fun linksAndImagesKeepTheirLabel() {
        assertEquals("See the docs and the shot.", SummaryText.shown("See [the docs](https://x.dev) and ![the shot](a.png)."))
    }

    @Test
    fun aLeadingHashOrQuoteGoes() {
        assertEquals("Heading", SummaryText.shown("## Heading"))
        assertEquals("Quoted", SummaryText.shown("> Quoted"))
        // Only at the start.
        assertEquals("Issue #4 > 3", SummaryText.shown("Issue #4 > 3"))
    }

    @Test
    fun anUnmatchedSymbolShowsAsWritten() {
        assertEquals(listOf(SummaryRun("2 * 3 and a ` tick", Plain)), SummaryText.runs("2 * 3 and a ` tick"))
    }

    @Test
    fun clippingCountsTheCharactersShown() {
        // 11 characters shown, though the text is 17 long.
        assertEquals("Run `bun` **now**", SummaryText.clip("Run `bun` **now**", 12))
        val clipped = SummaryText.clip("Run `bun test auth` then **fix it all**", 20)
        assertEquals("Run `bun test auth`…", clipped)
        assertTrue(StatusSummary.length(SummaryText.shown(clipped)) <= 20)
    }

    @Test
    fun clippingInsideARunKeepsItsStyle() {
        assertEquals("Look at `averyveryverylongname`…", SummaryText.clip("Look at `averyveryverylongnamethatgoeson`", 30))
    }

    @Test
    fun nowSizeCountsTheCharactersShown() {
        // 87 characters shown, 93 written: still display.
        val now = "`" + "a".repeat(40) + "` **" + "b".repeat(40) + "** ccccc"
        assertEquals(87, SummaryText.shown(now).length)
        assertEquals(NowSize.Display, NowSize.forText(now))
    }

    @Test
    fun aLinkLineGivesALabelAndAUrl() {
        assertEquals(
            SummaryLink("Dev server", "http://localhost:5173/login"),
            SummaryLinks.fromLine("Dev server | http://localhost:5173/login"),
        )
    }

    @Test
    fun aLinkLineWithOnlyAUrlUsesItsHost() {
        assertEquals(
            SummaryLink("github.com", "https://www.github.com/o/r/pull/1"),
            SummaryLinks.fromLine("https://www.github.com/o/r/pull/1"),
        )
    }

    @Test
    fun aLinkLineThatIsntWebIsLeftOut() {
        assertEquals(null, SummaryLinks.fromLine("Notes | file:///tmp/notes.md"))
        assertEquals(null, SummaryLinks.fromLine("Notes | /api/experimental/artifacts/1"))
    }

    @Test
    fun linksInASentenceStayAsTheirLabelOrHost() {
        val (text, links) = SummaryLinks.pull("Open [the login page](http://localhost:5173/login) or https://example.com/a, then check.")
        assertEquals("Open the login page or example.com, then check.", text)
        assertEquals(
            listOf(SummaryLink("the login page", "http://localhost:5173/login"), SummaryLink("example.com", "https://example.com/a")),
            links,
        )
    }

    @Test
    fun theCardTakesTheFirstTwoLinkLinesThenSentenceLinksEachUrlOnce() {
        val reply =
            "## Login\nNow: The form is at http://localhost:5173/login now.\nNext: Read [the PR](https://github.com/o/r/pull/1).\n" +
                "Link: Dev server | http://localhost:5173/login\nLink: ftp://nope\nLink: PR | https://github.com/o/r/pull/1\n" +
                "Link: Third | https://example.com"
        val card = StatusSummary.parse(reply) as StatusSummary.Card
        assertEquals("The form is at localhost:5173 now.", card.nowText)
        assertEquals("Read the PR.", card.next)
        assertEquals(
            listOf(SummaryLink("Dev server", "http://localhost:5173/login"), SummaryLink("PR", "https://github.com/o/r/pull/1")),
            card.links,
        )
    }

    @Test
    fun aReplyWithoutLinksHasNone() {
        val card = StatusSummary.parse("## Task\nNow: Working.") as StatusSummary.Card
        assertTrue(card.links.isEmpty())
    }

    @Test
    fun localUrlsAreRecognised() {
        assertTrue(SummaryLinks.isLocal("http://localhost:5173/login"))
        assertTrue(SummaryLinks.isLocal("http://127.0.0.1:8080"))
        assertTrue(SummaryLinks.isLocal("http://0.0.0.0:3000/"))
        assertFalse(SummaryLinks.isLocal("https://localhost.example.com"))
        assertFalse(SummaryLinks.isLocal("https://github.com"))
    }

    @Test
    fun aLocalUrlOpensOnTheComputerWithItsPortAndPath() {
        assertEquals(
            "http://192.168.1.20:5173/login?next=%2Fhome#top",
            SummaryLinks.onComputer("http://localhost:5173/login?next=%2Fhome#top", "192.168.1.20"),
        )
        assertEquals("http://mbp.local/", SummaryLinks.onComputer("http://127.0.0.1/", "mbp.local"))
        assertEquals("http://[fd00::1]:3000/a", SummaryLinks.onComputer("http://0.0.0.0:3000/a", "fd00::1"))
        assertEquals("192.168.1.20:5173", SummaryLinks.host(SummaryLinks.onComputer("http://localhost:5173/login", "192.168.1.20")))
    }

    @Test
    fun aSavedCardKeepsItsLinks() {
        val card = StatusSummary.parse("## Login\nNow: Done.\nLink: PR | https://github.com/o/r/pull/1")
        val saved = SavedSummary("s1", card, 1, "2026-11-06", "/p")
        // As the app reads its settings: a card saved with fields it no longer has (steps) still reads.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertEquals(saved, json.decodeFromString(SavedSummary.serializer(), json.encodeToString(SavedSummary.serializer(), saved)))
        // A card saved before links and Now lines existed still reads: no links, Now as one plain line.
        val old = """{"sessionId":"s1","summary":{"type":"card","title":"T","now":"N","next":null,"steps":[]},"updatedAt":1}"""
        val read = json.decodeFromString(SavedSummary.serializer(), old).summary as StatusSummary.Card
        assertEquals(emptyList<SummaryLink>(), read.links)
        assertEquals(listOf(NowLine("N")), read.now)
    }
}
