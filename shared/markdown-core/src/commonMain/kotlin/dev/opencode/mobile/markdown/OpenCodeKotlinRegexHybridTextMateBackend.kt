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
        buffer: PreparedTextMateLineBuffer,
        startIndex: Int,
        endIndex: Int,
        contiguousAnchor: Int?,
        metricsSink: OpenCodeTextMateMetricsSink,
    ): TextMateRegexMatch? {
        val input = buffer.source
        return specialMatch(pattern, input, startIndex, endIndex, contiguousAnchor, metricsSink)
            ?: KotlinRegexTextMateRegexBackend.find(
                pattern,
                buffer,
                startIndex,
                endIndex,
                contiguousAnchor,
                metricsSink,
            )
    }

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
                        .find(
                            suffix,
                            PreparedTextMateLineBuffer.prepare(input, metricsSink),
                            startIndex,
                            endIndex,
                            contiguousAnchor,
                            metricsSink,
                        )?.takeIf { it.start == startIndex }
                }
            }
            else -> null
        }
}

@Deprecated(
    message = "Use OpenCodeKotlinRegexHybridTextMateBackend for the Kotlin Regex hybrid shim.",
    replaceWith = ReplaceWith("OpenCodeKotlinRegexHybridTextMateBackend"),
)
typealias OpenCodeOnigurumaRegexBackend = OpenCodeKotlinRegexHybridTextMateBackend
