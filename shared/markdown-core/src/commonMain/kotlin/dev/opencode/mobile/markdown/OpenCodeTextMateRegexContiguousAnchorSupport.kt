package dev.opencode.mobile.markdown

internal object OpenCodeTextMateRegexContiguousAnchorSupport {
    private val contiguousLookbehindPrefix = Regex("""^\(\?<=\\G(?:\|[^)]*)?\)""")

    fun requiresContiguousAnchor(pattern: String): Boolean =
        pattern == "\\G" ||
            pattern.startsWith("\\G") ||
            pattern.contains("(?<=\\G)") ||
            pattern.contains("(?<!\\G)")

    fun effectivePattern(
        pattern: String,
        startIndex: Int,
        contiguousAnchor: Int?,
    ): String {
        if (contiguousAnchor == null || startIndex != contiguousAnchor) return pattern
        return contiguousLookbehindPrefix.replaceFirst(pattern, "")
    }

    fun rejectIfAnchorMismatch(
        pattern: String,
        startIndex: Int,
        contiguousAnchor: Int?,
    ): Boolean {
        if (contiguousAnchor == null || !requiresContiguousAnchor(pattern)) return false
        return startIndex != contiguousAnchor
    }

    fun contiguousAnchorStartMatch(
        startIndex: Int,
        endIndex: Int,
        contiguousAnchor: Int?,
    ): TextMateRegexMatch? {
        val anchor = contiguousAnchor ?: startIndex
        if (startIndex != anchor || startIndex > endIndex) return null
        return TextMateRegexMatch(
            start = startIndex,
            end = startIndex,
            captures = listOf(TextMateRegexCapture(0, startIndex, startIndex)),
        )
    }

    fun enforceContiguousAnchor(
        pattern: String,
        match: TextMateRegexMatch?,
        contiguousAnchor: Int?,
    ): TextMateRegexMatch? {
        if (match == null || contiguousAnchor == null) return match
        if (!pattern.contains("(?<=\\G)") && !pattern.contains("(?<!\\G)")) return match
        return match.takeIf { it.start == contiguousAnchor }
    }
}
