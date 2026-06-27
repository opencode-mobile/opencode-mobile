package dev.opencode.mobile.markdown

/**
 * Temporary Kotlin [Regex] shim used for regression comparisons. Production iOS uses
 * [OpenCodeLibOnigRegexBackend]; Android uses [OpenCodeJoniRegexBackend].
 */
object OpenCodeKotlinRegexHybridTextMateBackend : OpenCodeTextMateRegexBackend {
    override val name: String = "kotlin-regex-hybrid-textmate-bridge"

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

@Deprecated(
    message = "Use OpenCodeKotlinRegexHybridTextMateBackend for the Kotlin Regex hybrid shim.",
    replaceWith = ReplaceWith("OpenCodeKotlinRegexHybridTextMateBackend"),
)
typealias OpenCodeOnigurumaRegexBackend = OpenCodeKotlinRegexHybridTextMateBackend
