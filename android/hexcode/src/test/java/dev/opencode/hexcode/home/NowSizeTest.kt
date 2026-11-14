package dev.opencode.hexcode.home

import dev.opencode.hexcode.session.NowLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** app-plan.md, Now size: the Now sentence's type steps down with its length, then until the card fits. */
class NowSizeTest {
    @Test
    fun theSizeStepsDownWithLength() {
        assertEquals(NowSize.Display, NowSize.forLength(0))
        assertEquals(NowSize.Display, NowSize.forLength(90))
        assertEquals(NowSize.Large, NowSize.forLength(91))
        assertEquals(NowSize.Large, NowSize.forLength(150))
        assertEquals(NowSize.Medium, NowSize.forLength(151))
        assertEquals(NowSize.Medium, NowSize.forLength(220))
        assertEquals(NowSize.Small, NowSize.forLength(221))
        assertEquals(NowSize.Small, NowSize.forLength(300))
    }

    @Test
    fun lengthCountsCharactersAsTheUserSeesThem() {
        // An e with a combining accent is two chars in the string but one character on screen.
        assertEquals(NowSize.Display, NowSize.forText("e\u0301".repeat(90)))
        assertEquals(NowSize.Large, NowSize.forText("e\u0301".repeat(91)))
    }

    @Test
    fun aCardThatFitsKeepsItsSize() {
        assertEquals(NowSize.Fitted(NowSize.Large, fits = true), NowSize.fit(NowSize.Large) { true })
    }

    @Test
    fun aCardThatDoesntFitStepsDownUntilItDoes() {
        val tried = mutableListOf<NowSize>()
        val fitted =
            NowSize.fit(NowSize.Medium) {
                tried += it
                it == NowSize.Fit
            }
        assertEquals(NowSize.Fitted(NowSize.Fit, fits = true), fitted)
        assertEquals(listOf(NowSize.Medium, NowSize.Small, NowSize.Fit), tried)
    }

    @Test
    fun aCardThatNeverFitsEndsAtFitWithAnEllipsis() {
        assertEquals(NowSize.Fitted(NowSize.Fit, fits = false), NowSize.fit(NowSize.Display) { false })
    }

    @Test
    fun eachLineAfterTheFirstCountsAs20MoreCharacters() {
        // 60 + 20: display; a second line of 20 makes 60 + 20 + 20 = 100: nowLarge.
        assertEquals(NowSize.Display, NowSize.forLines(listOf(NowLine("a".repeat(60)), NowLine("b".repeat(10)))))
        assertEquals(NowSize.Large, NowSize.forLines(listOf(NowLine("a".repeat(60)), NowLine("b".repeat(20)))))
        // Markers and backticks don't count.
        assertEquals(NowSize.Display, NowSize.forLines(listOf(NowLine("`" + "a".repeat(90) + "`"))))
    }

    @Test
    fun aNowWithBulletsIsAtMostNowMedium() {
        assertEquals(
            NowSize.Medium,
            NowSize.forLines(listOf(NowLine("Two things:"), NowLine("a", bullet = true), NowLine("b", bullet = true))),
        )
        val long = List(4) { NowLine("x".repeat(60), bullet = true) }
        assertEquals(NowSize.Small, NowSize.forLines(listOf(NowLine("Lead.")) + long))
    }
}
