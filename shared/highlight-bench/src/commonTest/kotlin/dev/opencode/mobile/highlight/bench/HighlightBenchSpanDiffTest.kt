package dev.opencode.mobile.highlight.bench

import kotlin.test.Test
import kotlin.test.assertEquals

class HighlightBenchSpanDiffTest {
    @Test
    fun identicalColoringSplitDifferentlyAgrees() {
        val baseline = listOf(HighlightBenchSpan(0, 6, "Keyword"))
        val candidate = listOf(HighlightBenchSpan(0, 3, "Keyword"), HighlightBenchSpan(3, 6, "Keyword"))

        val comparison = HighlightBenchSpanDiff.compare(10, baseline, candidate)

        assertEquals(0, comparison.differingRangeCount)
        assertEquals(1.0, comparison.agreement)
    }

    @Test
    fun differingRolesAndPlainTextFormMaximalRanges() {
        val baseline = listOf(HighlightBenchSpan(0, 4, "Keyword"), HighlightBenchSpan(6, 8, "String"))
        val candidate = listOf(HighlightBenchSpan(0, 2, "Keyword"), HighlightBenchSpan(2, 4, "Type"), HighlightBenchSpan(4, 8, "String"))

        val comparison = HighlightBenchSpanDiff.compare(10, baseline, candidate)

        assertEquals(listOf(HighlightBenchRange(2, 6)), comparison.differingRanges)
        assertEquals(4, comparison.differingCharacters)
        assertEquals(0.6, comparison.agreement, 1e-9)
    }

    @Test
    fun spansPastTheSourceAreClampedAndATrailingDifferenceCloses() {
        val comparison = HighlightBenchSpanDiff.compare(5, emptyList(), listOf(HighlightBenchSpan(3, 99, "Comment")))

        assertEquals(listOf(HighlightBenchRange(3, 5)), comparison.differingRanges)
        assertEquals(1.0, HighlightBenchSpanDiff.compare(0, emptyList(), emptyList()).agreement)
    }
}
