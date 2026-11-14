package dev.opencode.hexcode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** app-plan.md, Reading the reply: best effort, whatever parts can be recognised show. */
class StatusSummaryBestEffortTest {
    private fun card(reply: String) = StatusSummary.parse(reply.trimIndent()) as StatusSummary.Card

    /** The real reply from the Pixel that fell back to raw text: a title, no `Now:` label. */
    @Test
    fun aTitleWithoutANowLabelReadsTheLinesAfterIt() {
        val card =
            card(
                """
                ## Research and prototype six mobile features
                Not started yet: six subagents must each research one feature, build a prototype with the prototype skill, and publish it via publish-artifact, each with a brief usage example.
                - Subagents: pairing, steering, approvals, resume/reconnect, voice, media
                """,
            )
        assertEquals("Research and prototype six mobile features", card.title)
        assertEquals(2, card.now.size)
        assertTrue(card.now[0].text.startsWith("Not started yet: six subagents"))
        assertEquals(NowLine("Subagents: pairing, steering, approvals, resume/reconnect, voice, media", bullet = true), card.now[1])
    }

    @Test
    fun boldLabels() {
        val card = card("## Topic\n**Now:** Tests pass.\n**Next:** Commit it")
        assertEquals(listOf(NowLine("Tests pass.")), card.now)
        assertEquals("Commit it", card.next)
    }

    @Test
    fun labelVariantsWithADash() {
        val card = card("## Topic\nStatus - Tests pass.\nNext steps — Commit it\nSource: https://example.com/a")
        assertEquals(listOf(NowLine("Tests pass.")), card.now)
        assertEquals("Commit it", card.next)
        assertEquals(listOf("https://example.com/a"), card.links.map { it.url })
        assertEquals("Done.", card("## T\nTL;DR: Done.").nowText)
        assertEquals("Ship it", card("## T\nSummary: Done.\nFollow-up: Ship it").next)
        assertEquals("Ship it", card("## T\nNow: Done.\nSuggested: Ship it").next)
    }

    @Test
    fun aWordThatOnlyStartsLikeALabelIsText() {
        assertEquals(
            listOf(
                "Status-quo stays.",
                "Next-gen later.",
            ),
            card("## T\nNow: Done.\nStatus-quo stays.\nNext-gen later.").now.drop(1).map {
                it.text
            },
        )
    }

    @Test
    fun oneToThreeHashesAreATitle() {
        assertEquals("Title", card("### Title\nNow: Done.").title)
        assertEquals("Title", card("# Title\nNow: Done.").title)
    }

    @Test
    fun aShortBoldFirstLineIsATitle() {
        val card = card("**Login fix**\nThe token refresh now retries once.")
        assertEquals("Login fix", card.title)
        assertEquals(listOf(NowLine("The token refresh now retries once.")), card.now)
    }

    @Test
    fun aShortFirstLineBeforeANowLineIsATitle() {
        val card = card("Login fix\nNow: The token refresh retries once.")
        assertEquals("Login fix", card.title)
        assertEquals("The token refresh retries once.", card.nowText)
    }

    @Test
    fun plusBulletsAndLinkLines() {
        val card =
            card(
                """
                ## Options
                Now: Two ways to do it:
                + Retry once
                + Ask the user
                https://example.com/docs
                [The PR](https://github.com/o/r/pull/1)
                Next: Pick one
                """,
            )
        assertEquals(listOf(false, true, true), card.now.map { it.bullet })
        assertEquals(listOf("https://example.com/docs", "https://github.com/o/r/pull/1"), card.links.map { it.url })
        assertEquals("The PR", card.links[1].label)
    }

    @Test
    fun unrecognisedLinesBeforeNextAreKeptAfterNextIgnored() {
        val card = card("## T\nNow: Done.\nOne more thing.\nNext: Commit it\nHope this helps!")
        assertEquals(listOf("Done.", "One more thing."), card.now.map { it.text })
        assertEquals("Commit it", card.next)
    }

    @Test
    fun aNowLabelWithoutATitleHasNoTitle() {
        val card = card("Now: Done, and 4 tests pass in the suite that covers every login path.\nNext: Commit it")
        assertEquals("", card.title)
        assertEquals("Commit it", card.next)
    }

    @Test
    fun noTitleAndNoNowIsAStatusCardWithEveryLineAsNow() {
        val fallback =
            StatusSummary.parse(
                "I looked at the code and found the retry.\n- `client.ts` retries once\nNext: Commit it",
            ) as StatusSummary.Fallback
        assertEquals(
            listOf(NowLine("I looked at the code and found the retry."), NowLine("`client.ts` retries once", bullet = true)),
            fallback.now,
        )
        assertEquals("Commit it", fallback.next)
        // Nothing is cut: more than 5 lines stay.
        val long = StatusSummary.parse((1..8).joinToString("\n") { "Line $it." }) as StatusSummary.Fallback
        assertEquals(8, long.now.size)
    }

    @Test
    fun aFallbackSavedBeforeReadsAsOneLine() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = """{"sessionId":"s1","summary":{"type":"fallback","text":"Plain words."},"updatedAt":1}"""
        val read = json.decodeFromString(SavedSummary.serializer(), old).summary as StatusSummary.Fallback
        assertEquals(listOf(NowLine("Plain words.")), read.now)
        assertNull(read.next)
    }
}
