package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same vectors as iOS `MessageTextLimitTests`. */
class MessageTextLimitTest {
    /** "line 1" … "line n", one per line. */
    private fun lines(count: Int): String = (1..count).joinToString("\n") { "line $it" }

    /** "abcdef abcdef …" cut to [count] characters: a word every 7. */
    private fun words(count: Int): String = "abcdef ".repeat(count / 7 + 1).take(count)

    @Test
    fun constants() {
        assertEquals(40, MessageTextLimit.CHARACTERS_PER_LINE)
        assertEquals(25, MessageTextLimit.LIMIT)
        assertEquals(30, MessageTextLimit.TOLERANCE)
    }

    @Test
    fun empty_showsInFull() {
        assertNull(MessageTextLimit.cut(""))
    }

    @Test
    fun short_showsInFull() {
        assertNull(MessageTextLimit.cut("Hello"))
    }

    @Test
    fun exactlyTheLimit_showsInFull() {
        assertNull(MessageTextLimit.cut(lines(25)))
    }

    @Test
    fun withinTheTolerance_showsInFull() {
        (26..30).forEach { count -> assertNull("$count lines", MessageTextLimit.cut(lines(count))) }
    }

    @Test
    fun oneLinePastTheTolerance_cutsToTheLimit() {
        val shown = lines(25)
        assertEquals(MessageTextCut(shown, shown.length), MessageTextLimit.cut(lines(31)))
    }

    @Test
    fun overByLineBreaks_keepsTheFirstWholeLines() {
        val shown = lines(25)
        assertEquals(MessageTextCut(shown, shown.length), MessageTextLimit.cut(lines(100)))
    }

    @Test
    fun blankLinesCountAsLines() {
        val text = "first" + "\n".repeat(40) + "last"
        assertEquals(MessageTextCut("first", 5), MessageTextLimit.cut(text))
    }

    @Test
    fun manyLeadingBlankLines_showTheContentAfterThem() {
        // 40 blank lines alone are over the tolerance; the shown part starts at the content.
        val shown = lines(25)
        assertEquals(MessageTextCut(shown, 40 + shown.length), MessageTextLimit.cut("\n".repeat(40) + lines(100)))
        assertEquals(MessageTextCut("hello", 45), MessageTextLimit.cut("\n".repeat(40) + "hello"))
    }

    @Test
    fun leadingWhitespaceOnlyLines_areSkippedButIndentationIsKept() {
        val text = "  \n".repeat(40) + "    indented\nmore"
        assertEquals(MessageTextCut("    indented\nmore", text.length), MessageTextLimit.cut(text))
    }

    @Test
    fun fewLeadingBlankLines_stillStartTheCutAtTheContent() {
        val shown = lines(25)
        assertNull(MessageTextLimit.cut("\n\nhello"))
        assertEquals(MessageTextCut(shown, 2 + shown.length), MessageTextLimit.cut("\n\n" + lines(100)))
    }

    @Test
    fun onlyBlankLines_showNothing() {
        assertEquals(MessageTextCut("", 0), MessageTextLimit.cut("\n".repeat(100)))
    }

    @Test
    fun multiMegabyteBlankPaste_stopsLookingForContent() {
        val text = CountingText("\n".repeat(3_000_000) + "late")

        assertEquals(MessageTextCut("", 0), MessageTextLimit.cut(text))
        assertTrue("read ${text.reads}", text.reads <= MAXIMUM_READS + MessageTextLimit.MAXIMUM_BLANK_SKIP + 1)
    }

    @Test
    fun unbrokenLineAtTheTolerance_showsInFull() {
        assertNull(MessageTextLimit.cut("a".repeat(1_200)))
    }

    @Test
    fun unbrokenLinePastTheTolerance_isCutAtTheLimit() {
        val expected = MessageTextCut("a".repeat(1_000) + "…", 1_000, endsMidLine = true)
        assertEquals(expected, MessageTextLimit.cut("a".repeat(1_201)))
        assertEquals(expected, MessageTextLimit.cut("a".repeat(5_000)))
    }

    @Test
    fun mixed_keepsWholeLinesThenCutsTheLongLineAtAWord() {
        // 20 short lines (cost 20) and a 600-character line (cost 15): 35 > 30.
        val text = lines(20) + "\n" + words(600)
        // 5 lines left = 200 characters; the last word that ends inside them ends at 195.
        val partial = words(195)
        val cut = MessageTextLimit.cut(text)
        assertEquals(lines(20) + "\n" + partial + "…", cut?.visibleText)
        assertEquals(lines(20).length + 1 + 195, cut?.shownCharacters)
        assertTrue(partial.endsWith("abcdef"))
    }

    @Test
    fun wordEndingExactlyAtTheCut_isKeptWhole() {
        // Character 1,000 is a space, so the first 1,000 characters end on a whole word.
        assertEquals(MessageTextCut(words(1_000) + "…", 1_000, endsMidLine = true), MessageTextLimit.cut(words(2_000)))
    }

    @Test
    fun wordBoundaryNearTheLineStart_isIgnored() {
        val text = "a".repeat(10) + " " + "b".repeat(2_000)
        val kept = "a".repeat(10) + " " + "b".repeat(989)
        assertEquals(MessageTextCut("$kept…", 1_000, endsMidLine = true), MessageTextLimit.cut(text))
    }

    @Test
    fun crlfLineBreaks_countLikeLineFeeds() {
        // 40 characters fill one line; the CR of a CRLF must not start a second one (iOS reads
        // "\r\n" as one Character).
        val line = "a".repeat(40)
        assertNull(MessageTextLimit.cut(List(30) { line }.joinToString("\r\n")))

        val cut = MessageTextLimit.cut(List(31) { line }.joinToString("\r\n"))
        assertEquals(List(25) { line }.joinToString("\r\n"), cut?.visibleText)
    }

    @Test
    fun cutNeverSplitsASurrogatePair() {
        // 999 letters, then emoji: the 1,000th unit is the first half of a pair.
        val text = "a".repeat(999) + "😀".repeat(200)
        val cut = MessageTextLimit.cut(text)
        assertEquals("a".repeat(999) + "…", cut?.visibleText)
        assertEquals(999, cut?.shownCharacters)
    }

    // Shared vectors with iOS `MessageTextLimitTests`: the cut, the shown count and the total
    // count are the same on both platforms (UTF-16 units), and no cut ends inside a grapheme
    // cluster. Both platforms segment these sequences alike; their grapheme rules can differ for
    // rarer ones (an Indic conjunct, say), where the cut may then land one cluster apart.

    /** Asserts the shown part of [text] and the two counts its row shows. */
    private fun assertCut(
        text: String,
        visibleText: String,
        shownCharacters: Int,
        totalCharacters: Int,
    ) {
        val cut = MessageTextLimit.cut(text)
        assertEquals(visibleText, cut?.visibleText)
        assertEquals(shownCharacters, cut?.shownCharacters)
        assertEquals(totalCharacters, text.length)
    }

    @Test
    fun emojiOnlyLines_costTheirUtf16Units() {
        val line = "😀".repeat(10)
        assertCut(List(40) { line }.joinToString("\n"), List(25) { line }.joinToString("\n"), 524, 839)
    }

    @Test
    fun emojiOnlyLine_isCutAtTheLimitOfUnits() {
        assertCut("😀".repeat(700), "😀".repeat(500) + "…", 1_000, 1_400)
        // The 1,000th unit is the first half of an emoji: the cut keeps the emoji before it.
        assertCut("a" + "😀".repeat(700), "a" + "😀".repeat(499) + "…", 999, 1_401)
    }

    @Test
    fun mixedTextAndEmoji_cutsAtAWord() {
        // 20 short lines (cost 20) and an 800-unit line (cost 20): 5 lines left = 200 units.
        val text = lines(20) + "\n" + "word 😀 ".repeat(100)
        assertCut(text, lines(20) + "\n" + "word 😀 ".repeat(24) + "word 😀…", 350, 951)
    }

    @Test
    fun zwjFamilyAtTheCut_isKeptWhole() {
        val family = "👨‍👩‍👧‍👦"
        assertEquals(11, family.length)
        assertCut("a".repeat(995) + family + "b".repeat(300), "a".repeat(995) + "…", 995, 1_306)
    }

    @Test
    fun flagsAtTheCut_areKeptWhole() {
        // The cut falls between the two regional indicators of the first flag.
        assertCut("a".repeat(998) + "🇺🇸🇫🇷" + "b".repeat(300), "a".repeat(998) + "…", 998, 1_306)
        // The cut falls between the two flags.
        assertCut("a".repeat(996) + "🇺🇸🇫🇷" + "b".repeat(300), "a".repeat(996) + "🇺🇸…", 1_000, 1_304)
    }

    @Test
    fun skinToneAndVariationSelectorAtTheCut_stayWithTheirEmoji() {
        assertCut("a".repeat(998) + "👍🏻" + "b".repeat(300), "a".repeat(998) + "…", 998, 1_302)
        assertCut("a".repeat(999) + "❤\uFE0F" + "b".repeat(300), "a".repeat(999) + "…", 999, 1_301)
    }

    @Test
    fun combiningMarkAtTheCut_staysWithItsLetter() {
        assertCut("a".repeat(999) + "e\u0301" + "b".repeat(300), "a".repeat(999) + "…", 999, 1_301)
    }

    @Test
    fun cjkText_countsOneUnitPerCharacter() {
        assertCut("中文".repeat(700), "中文".repeat(500) + "…", 1_000, 1_400)
        // 50-character lines cost 2: twelve whole lines, then 40 characters of the 13th.
        val line = "中".repeat(50)
        assertCut(List(40) { line }.joinToString("\n"), List(12) { line }.joinToString("\n") + "\n" + "中".repeat(40) + "…", 652, 2_039)
    }

    @Test
    fun multiMegabyteLog_stopsReadingEarly() {
        // About 3.2 MB of 35-character log lines.
        val line = "2026-01-01T00:00:00Z INFO worker ok"
        val text = CountingText(List(90_000) { line }.joinToString("\n"))

        val cut = MessageTextLimit.cut(text)

        val shown = List(25) { line }.joinToString("\n")
        assertEquals(MessageTextCut(shown, shown.length), cut)
        assertTrue(text.length > 3_000_000)
        assertTrue("read ${text.reads}", text.reads <= MAXIMUM_READS)
    }

    @Test
    fun multiMegabyteSingleLine_stopsReadingEarly() {
        val text = CountingText("x".repeat(3_000_000))

        val cut = MessageTextLimit.cut(text)

        assertEquals(1_000, cut?.shownCharacters)
        assertTrue("read ${text.reads}", text.reads <= MAXIMUM_READS)
    }

    /** Characters of a text, counting how many were read. */
    private class CountingText(
        private val text: String,
    ) : CharSequence {
        var reads = 0
            private set

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            reads += 1
            return text[index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence = text.subSequence(startIndex, endIndex)

        override fun toString(): String = text
    }

    private companion object {
        /**
         * The pass reads at most `TOLERANCE` lines' worth of characters plus their breaks; the
         * cut then looks back over at most one cut line for a word boundary.
         */
        const val MAXIMUM_READS =
            MessageTextLimit.TOLERANCE * MessageTextLimit.CHARACTERS_PER_LINE + MessageTextLimit.TOLERANCE + 1 +
                MessageTextLimit.LIMIT * MessageTextLimit.CHARACTERS_PER_LINE + 2
    }
}
