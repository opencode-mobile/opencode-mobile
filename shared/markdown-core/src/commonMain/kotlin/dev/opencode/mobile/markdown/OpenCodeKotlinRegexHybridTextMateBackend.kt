package dev.opencode.mobile.markdown

/**
 * Temporary Kotlin [Regex] shim used for regression comparisons. Production iOS uses
 * `OpenCodeLibOnigRegexRuntime`; Android uses `OpenCodeJoniRegexRuntime`.
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
        metricsSink: OpenCodeTextMateMetricsSink,
    ): TextMateRegexMatch? =
        specialMatch(pattern, input, startIndex, endIndex, contiguousAnchor, metricsSink)
            ?: KotlinRegexTextMateRegexBackend.find(
                pattern,
                input,
                startIndex,
                endIndex,
                contiguousAnchor,
                metricsSink,
            )

    private fun specialMatch(
        pattern: String,
        input: String,
        startIndex: Int,
        endIndex: Int,
        contiguousAnchor: Int?,
        metricsSink: OpenCodeTextMateMetricsSink,
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
                        .find(suffix, input, startIndex, endIndex, contiguousAnchor, metricsSink)
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
