package dev.opencode.mobile.ui.components

import dev.opencode.mobile.markdown.CodeSpan
import dev.opencode.mobile.markdown.CodeTokenRole
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeCodeLinesTest {
    @Test
    fun splitsOnNewlinesAndIgnoresSingleTrailingNewline() {
        assertEquals(listOf("a", "", "b"), OpenCodeCodeLines.split("a\n\nb\n").map { it.text })
        assertEquals(listOf("a", "b"), OpenCodeCodeLines.split("a\r\nb").map { it.text })
        assertEquals(listOf("a", ""), OpenCodeCodeLines.split("a\n\n").map { it.text })
    }

    @Test
    fun clipsCrossLineSpansPerLine() {
        val lines =
            OpenCodeCodeLines.split(
                "val x\n/* a\nb */ y",
                listOf(
                    CodeSpan(0, 3, CodeTokenRole.Keyword),
                    CodeSpan(6, 15, CodeTokenRole.Comment),
                ),
            )
        assertEquals(listOf(CodeSpan(0, 3, CodeTokenRole.Keyword)), lines[0].spans)
        assertEquals(listOf(CodeSpan(0, 4, CodeTokenRole.Comment)), lines[1].spans)
        assertEquals(listOf(CodeSpan(0, 4, CodeTokenRole.Comment)), lines[2].spans)
    }

    @Test
    fun expandsTabsAndShiftsSpans() {
        val line = OpenCodeCodeLines.split("\tfoo", listOf(CodeSpan(1, 4, CodeTokenRole.Function))).single()
        assertEquals("    foo", line.text)
        assertEquals(listOf(CodeSpan(4, 7, CodeTokenRole.Function)), line.spans)
    }

    @Test
    fun cutsLinesPastDisplayLimitAndClipsSpans() {
        val max = OpenCodeCodeLines.MAX_DISPLAY_LINE_LENGTH
        val long = "x".repeat(max + 2_000)
        val source = "short\n$long\nend"
        val lines =
            OpenCodeCodeLines.split(
                source,
                listOf(
                    // Starts inside the shown part and runs past the cut.
                    CodeSpan(6 + max - 10, 6 + max + 100, CodeTokenRole.String),
                    // Entirely past the cut.
                    CodeSpan(6 + max + 500, 6 + max + 600, CodeTokenRole.Keyword),
                    CodeSpan(source.length - 3, source.length, CodeTokenRole.Function),
                ),
            )

        assertEquals(3, lines.size)
        assertEquals("short", lines[0].text)
        assertEquals("x".repeat(max) + OpenCodeCodeLines.CUT_MARKER, lines[1].text)
        assertEquals(listOf(CodeSpan(max - 10, max, CodeTokenRole.String)), lines[1].spans)
        assertEquals("end", lines[2].text)
        assertEquals(listOf(CodeSpan(0, 3, CodeTokenRole.Function)), lines[2].spans)
    }

    @Test
    fun keepsLinesAtDisplayLimitWhole() {
        val exact = "y".repeat(OpenCodeCodeLines.MAX_DISPLAY_LINE_LENGTH)
        assertEquals(exact, OpenCodeCodeLines.split(exact).single().text)
    }

    @Test
    fun cutsAfterTabExpansionAndNeverSplitsSurrogatePairs() {
        val max = OpenCodeCodeLines.MAX_DISPLAY_LINE_LENGTH
        // 2,000 tabs expand to 8,000 spaces: under the raw limit, over the display limit.
        val tabbed = OpenCodeCodeLines.split("\t".repeat(2_000)).single()
        assertEquals(" ".repeat(max) + OpenCodeCodeLines.CUT_MARKER, tabbed.text)

        // A surrogate pair straddling the cut is dropped whole.
        val emoji = OpenCodeCodeLines.split("a".repeat(max - 1) + "\uD83D\uDE00" + "b".repeat(10)).single()
        assertEquals("a".repeat(max - 1) + OpenCodeCodeLines.CUT_MARKER, emoji.text)
    }

    @Test
    fun cutsAMegabyteSingleLineFile() {
        val line = OpenCodeCodeLines.split("z".repeat(1_048_576)).single()
        assertEquals(OpenCodeCodeLines.MAX_DISPLAY_LINE_LENGTH + OpenCodeCodeLines.CUT_MARKER.length, line.text.length)
    }
}
