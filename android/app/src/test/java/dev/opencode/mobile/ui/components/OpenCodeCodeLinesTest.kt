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
}
