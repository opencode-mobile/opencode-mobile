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
    fun unbrokenLineAtTheTolerance_showsInFull() {
        assertNull(MessageTextLimit.cut("a".repeat(1_200)))
    }

    @Test
    fun unbrokenLinePastTheTolerance_isCutAtTheLimit() {
        val expected = MessageTextCut("a".repeat(1_000) + "…", 1_000)
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
        assertEquals(MessageTextCut(words(1_000) + "…", 1_000), MessageTextLimit.cut(words(2_000)))
    }

    @Test
    fun wordBoundaryNearTheLineStart_isIgnored() {
        val text = "a".repeat(10) + " " + "b".repeat(2_000)
        val kept = "a".repeat(10) + " " + "b".repeat(989)
        assertEquals(MessageTextCut("$kept…", 1_000), MessageTextLimit.cut(text))
    }

    @Test
    fun cutNeverSplitsASurrogatePair() {
        // 999 letters, then emoji: the 1,000th unit is the first half of a pair.
        val text = "a".repeat(999) + "😀".repeat(200)
        val cut = MessageTextLimit.cut(text)
        assertEquals("a".repeat(999) + "…", cut?.visibleText)
        assertEquals(999, cut?.shownCharacters)
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
