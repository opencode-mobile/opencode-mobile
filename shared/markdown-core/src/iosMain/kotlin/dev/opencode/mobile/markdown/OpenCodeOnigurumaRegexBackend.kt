package dev.opencode.mobile.markdown

/**
 * iOS TextMate regex backend. Kotlin [Regex] handles most patterns; a small set of
 * Oniguruma-specific anchors used by bundled grammars are handled explicitly.
 * This remains a Kotlin Regex fallback until the native Oniguruma binding lands.
 */
object OpenCodeOnigurumaRegexBackend : OpenCodeTextMateRegexBackend {
    override val name: String = "apple-kotlin-regex-textmate-bridge"

    override val isOnigurumaCompatible: Boolean = false

    override fun find(
        pattern: String,
        input: String,
        startIndex: Int,
        endIndex: Int,
    ): TextMateRegexMatch? =
        specialMatch(pattern, input, startIndex, endIndex)
            ?: KotlinRegexTextMateRegexBackend.find(pattern, input, startIndex, endIndex)

    private fun specialMatch(
        pattern: String,
        input: String,
        startIndex: Int,
        endIndex: Int,
    ): TextMateRegexMatch? =
        when {
            pattern == "\\G" -> anchoredStart(input, startIndex, endIndex)
            pattern.startsWith("\\G") -> anchoredPrefix(pattern.removePrefix("\\G"), input, startIndex, endIndex)
            else -> null
        }

    private fun anchoredStart(
        input: String,
        startIndex: Int,
        endIndex: Int,
    ): TextMateRegexMatch? =
        if (startIndex <= endIndex) {
            TextMateRegexMatch(start = startIndex, end = startIndex)
        } else {
            null
        }

    private fun anchoredPrefix(
        suffix: String,
        input: String,
        startIndex: Int,
        endIndex: Int,
    ): TextMateRegexMatch? {
        if (suffix.isEmpty()) return anchoredStart(input, startIndex, endIndex)
        return KotlinRegexTextMateRegexBackend
            .find(suffix, input, startIndex, endIndex)
            ?.takeIf { it.start == startIndex }
    }
}
