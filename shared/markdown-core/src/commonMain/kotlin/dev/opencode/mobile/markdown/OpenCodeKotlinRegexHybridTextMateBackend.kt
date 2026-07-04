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
        contiguousAnchor: Int?,
    ): TextMateRegexMatch? =
        specialMatch(pattern, input, startIndex, endIndex, contiguousAnchor)
            ?: KotlinRegexTextMateRegexBackend.find(pattern, input, startIndex, endIndex, contiguousAnchor)

    private fun specialMatch(
        pattern: String,
        input: String,
        startIndex: Int,
        endIndex: Int,
        contiguousAnchor: Int?,
    ): TextMateRegexMatch? =
        when {
            pattern == "\\G" ->
                OpenCodeTextMateRegexContiguousAnchorSupport.contiguousAnchorStartMatch(
                    startIndex = startIndex,
                    endIndex = endIndex,
                    contiguousAnchor = contiguousAnchor,
                )
            pattern.startsWith("\\G") -> {
                val suffix = pattern.removePrefix("\\G")
                if (suffix.isEmpty()) {
                    OpenCodeTextMateRegexContiguousAnchorSupport.contiguousAnchorStartMatch(
                        startIndex = startIndex,
                        endIndex = endIndex,
                        contiguousAnchor = contiguousAnchor,
                    )
                } else {
                    KotlinRegexTextMateRegexBackend
                        .find(suffix, input, startIndex, endIndex, contiguousAnchor)
                        ?.takeIf { it.start == startIndex }
                }
            }
            else -> null
        }

    private fun anchoredStart(
        input: String,
        startIndex: Int,
        endIndex: Int,
    ): TextMateRegexMatch? =
        OpenCodeTextMateRegexContiguousAnchorSupport.contiguousAnchorStartMatch(
            startIndex = startIndex,
            endIndex = endIndex,
            contiguousAnchor = null,
        )

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
