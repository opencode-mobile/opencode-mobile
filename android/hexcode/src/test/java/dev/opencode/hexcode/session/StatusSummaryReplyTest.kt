package dev.opencode.hexcode.session

import dev.opencode.hexcode.home.NowSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading version 4 replies (app-plan.md, Reading the reply, Clipping, Now size). The fixtures are real model output,
 * from status-summary-proposal.md §5.
 */
class StatusSummaryReplyTest {
    private fun card(reply: String) = StatusSummary.parse(reply.trimIndent()) as StatusSummary.Card

    private fun plain(text: String) = NowLine(text)

    private fun bullet(text: String) = NowLine(text, bullet = true)

    /** §5 A: the real session, a lead and two bullets, a Next to say and a Link line. */
    @Test
    fun aLeadWithBulletsAndALink() {
        val card =
            card(
                """
                ## Prototype: palette session dates
                Now: Four palette variants built and published: `0` current, `A` date buckets, `B` time gutter, `C` date filters; A recommended.
                - Source: `dialog.prototype.html` (untracked, next to `dialog.tsx`)
                - Published to this laptop only; Mac mini and Tailscale are down
                Next: Fold variant A into `dialog.tsx`
                Link: Open prototype | http://Ivans-MacBook-Pro.local:8787/2026-11-06/1343-command-palette--session-date-improvements/
                """,
            )
        assertEquals("Prototype: palette session dates", card.title)
        assertEquals(3, card.now.size)
        assertEquals(listOf(false, true, true), card.now.map { it.bullet })
        assertTrue(card.now[0].text.startsWith("Four palette variants built and published: `0` current"))
        assertEquals("Published to this laptop only; Mac mini and Tailscale are down", card.now[2].text)
        assertEquals("Fold variant A into `dialog.tsx`", card.next)
        assertEquals(listOf("Open prototype"), card.links.map { it.label })
        assertEquals(NowSize.Small, NowSize.forLines(card.now))
    }

    /** §5 B: bullets past the prompt's 50 characters stay whole (the fit makes room); `Link: none` is ignored. */
    @Test
    fun longBulletsStayWholeAndLinkNoneIsIgnored() {
        val card =
            card(
                """
                ## Validation for mathutils.py
                Now: All four `mathutils.py` functions now validate inputs, and 11 tests pass in `test_validation.py` and `test_mathutils.py`.
                - `clamp`, `average`, `percent`, and `scale` raise `TypeError` on non-numeric input
                - `clamp` raises `ValueError` when low > high; `average` on an empty list; `percent` when whole == 0
                Next: Commit these changes
                Link: none
                """,
            )
        assertEquals(3, card.now.size)
        assertEquals("`clamp` raises `ValueError` when low > high; `average` on an empty list; `percent` when whole == 0", card.now[2].text)
        assertEquals("Commit these changes", card.next)
        assertTrue(card.links.isEmpty())
    }

    /** §5 C: a list answer, a lead and four short bullets. */
    @Test
    fun aListAnswer() {
        val card =
            card(
                """
                ## Safer remove_item ideas
                Now: Four one-line ways to make `remove_item` safer, without changing code.
                - Validate non-negative `qty`
                - Treat missing item as zero
                - Clamp result to zero
                - Return a copy of stock
                Next: Apply one of these
                """,
            )
        assertEquals(
            listOf(
                plain("Four one-line ways to make `remove_item` safer, without changing code."),
                bullet("Validate non-negative `qty`"),
                bullet("Treat missing item as zero"),
                bullet("Clamp result to zero"),
                bullet("Return a copy of stock"),
            ),
            card.now,
        )
        assertEquals("Apply one of these", card.next)
        // 157 characters shown plus 4 × 20 for the lines after the first.
        assertEquals(NowSize.Small, NowSize.forLines(card.now))
    }

    /** §5 D: no Next line at all. */
    @Test
    fun noNextLine() {
        val card =
            card(
                """
                ## Example.com and README
                Now: Page title is "Example Domain" with no anchor link; README links to the Python unittest docs.
                Link: Python unittest docs | https://docs.python.org/3/library/unittest.html
                """,
            )
        assertNull(card.next)
        assertEquals(1, card.now.size)
        // 97 characters.
        assertEquals(NowSize.Large, NowSize.forLines(card.now))
    }

    /** §5 H: "Next: none" and "Link: none" mean nothing. */
    @Test
    fun nextNoneAndLinkNone() {
        val card =
            card(
                """
                ## Todo list task complete
                Now: All 4 steps done; `notes.md` contains one line for `add(a, b)`.
                - README.md read
                - math.ts read
                - notes.md written
                - notes.md verified
                Next: none
                Link: none
                """,
            )
        assertNull(card.next)
        assertTrue(card.links.isEmpty())
        assertEquals(4, card.now.count { it.bullet })
        // Short, but bullets cap it at nowMedium.
        assertEquals(NowSize.Medium, NowSize.forLines(card.now))
    }

    /** §5 H, the first draft: "None" and "Link: None | None". */
    @Test
    fun theNoneWords() {
        for (none in listOf("None", "none.", "N/A", "-", "—", "(none)", "")) {
            assertNull(none, card("## T\nNow: Done.\nNext: $none\nLink: None | None").next)
        }
        assertTrue(card("## T\nNow: Done.\nLink: None | None").links.isEmpty())
        assertEquals("None of these", card("## T\nNow: Done.\nNext: None of these").next)
    }

    @Test
    fun nowRunsUntilNextLinkOrATitle() {
        val card =
            card(
                """
                ## Topic
                Now:
                The lead comes on the next line.
                A second sentence.
                Link: Docs | https://example.com/docs
                Not part of Now.
                Next: Commit it
                Chatter after the card.
                """,
            )
        assertEquals(
            // A line after a Link line is kept rather than dropped (best effort).
            listOf(plain("The lead comes on the next line."), plain("A second sentence."), plain("Not part of Now.")),
            card.now,
        )
        assertEquals("Commit it", card.next)
    }

    @Test
    fun everyBulletMarker() {
        val card = card("## T\nNow: Lead.\n- a\n* b\n• c\n1. d\n2) e")
        assertEquals(listOf("Lead.", "a", "b", "c", "d"), card.now.map { it.text })
        assertEquals(listOf(false, true, true, true, true), card.now.map { it.bullet })
    }

    @Test
    fun atMostFourBulletsAndFiveLines() {
        // A fifth bullet is dropped, and the plain line after it still counts as the fifth line.
        val bullets = card("## T\nNow: Lead.\n- 1\n- 2\n- 3\n- 4\n- 5\nAfter.\nMore.")
        assertEquals(listOf("Lead.", "1", "2", "3", "4"), bullets.now.map { it.text })
        val lines = card("## T\nNow: 1.\nTwo.\nThree.\nFour.\nFive.\nSix.")
        assertEquals(5, lines.now.size)
    }

    @Test
    fun nowIsNotCutByCharacters() {
        // Over 300 characters: the card's fit, not the parser, makes it fit.
        val line = "word ".repeat(24).trim()
        val card = card("## T\nNow: $line\n$line\n$line\nKept.")
        assertEquals(listOf(line, line, line, "Kept."), card.now.map { it.text })
        assertTrue(card.now.none { it.text.endsWith("…") })
    }

    @Test
    fun linksInNowLinesBecomeTiles() {
        val card = card("## T\nNow: Lead.\n- See https://example.com/a.\nNext: Open [the PR](https://github.com/o/r/pull/1)")
        assertEquals("See example.com.", card.now[1].text)
        assertEquals("Open the PR", card.next)
        assertEquals(listOf("https://example.com/a", "https://github.com/o/r/pull/1"), card.links.map { it.url })
    }

    @Test
    fun aNowLabelWithNothingAfterItIsACardWithAnEmptyNow() {
        assertEquals(emptyList<NowLine>(), (StatusSummary.parse("## T\nNow:\nNext: Something") as StatusSummary.Card).now)
    }

    /** The final v4 prompt on the kept fixture session `prompt-lab long`, on OpenCode v2.2.5 (beam-free). */
    @Test
    fun theFixtureSessionsReply() {
        val card =
            StatusSummary.parse(
                "## Added low_stock function\nNow: `low_stock` returns names below a limit; 4 tests pass.\n" +
                    "- Added `low_stock(stock, limit)` to `inventory.py`\n- Added a test and ran `python3 -m unittest`\nNext: none",
            ) as StatusSummary.Card
        assertEquals("Added low_stock function", card.title)
        assertEquals(
            listOf(
                plain("`low_stock` returns names below a limit; 4 tests pass."),
                bullet("Added `low_stock(stock, limit)` to `inventory.py`"),
                bullet("Added a test and ran `python3 -m unittest`"),
            ),
            card.now,
        )
        assertNull(card.next)
        assertEquals(NowSize.Medium, NowSize.forLines(card.now))
    }
}
