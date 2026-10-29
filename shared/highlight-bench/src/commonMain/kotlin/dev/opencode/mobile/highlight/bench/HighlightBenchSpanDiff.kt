package dev.opencode.mobile.highlight.bench

/** A source range, [start] inclusive and [end] exclusive, in UTF-16 offsets. */
data class HighlightBenchRange(
    val start: Int,
    val end: Int,
)

/** Where two engines color the same source differently. */
class HighlightBenchSpanComparison(
    val length: Int,
    /** Maximal ranges where the roles differ (including colored against plain), in order. */
    val differingRanges: List<HighlightBenchRange>,
    val differingCharacters: Int,
) {
    val differingRangeCount: Int
        get() = differingRanges.size

    /** Share of characters both engines give the same role (plain counts as a role); 1 for empty text. */
    val agreement: Double
        get() = if (length == 0) 1.0 else 1.0 - differingCharacters.toDouble() / length
}

object HighlightBenchSpanDiff {
    /**
     * Compares two engines' spans for one source of [length] characters, character by character,
     * so the same coloring split into different spans counts as agreement. Spans are clamped to
     * the source; where one engine's spans overlap, the later one wins.
     */
    fun compare(
        length: Int,
        baseline: List<HighlightBenchSpan>,
        candidate: List<HighlightBenchSpan>,
    ): HighlightBenchSpanComparison {
        val roleIds = mutableMapOf<String, Int>()
        val baselineRoles = roles(length, baseline, roleIds)
        val candidateRoles = roles(length, candidate, roleIds)
        val ranges = mutableListOf<HighlightBenchRange>()
        var differing = 0
        var rangeStart = -1
        for (index in 0 until length) {
            if (baselineRoles[index] != candidateRoles[index]) {
                differing += 1
                if (rangeStart < 0) rangeStart = index
            } else if (rangeStart >= 0) {
                ranges += HighlightBenchRange(rangeStart, index)
                rangeStart = -1
            }
        }
        if (rangeStart >= 0) ranges += HighlightBenchRange(rangeStart, length)
        return HighlightBenchSpanComparison(length, ranges, differing)
    }

    /** Role id per character; 0 is plain. */
    private fun roles(
        length: Int,
        spans: List<HighlightBenchSpan>,
        roleIds: MutableMap<String, Int>,
    ): IntArray {
        val roles = IntArray(length)
        spans.forEach { span ->
            val role = roleIds.getOrPut(span.role) { roleIds.size + 1 }
            for (index in span.start.coerceIn(0, length) until span.end.coerceIn(0, length)) roles[index] = role
        }
        return roles
    }
}
