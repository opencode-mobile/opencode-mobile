package dev.opencode.mobile.markdown

/**
 * Joni rejects some TextMate negative lookbehinds that alternate zero-width `^`
 * with fixed-width branches. Rewrite them into chained fixed-width lookbehinds
 * with equivalent "must not be preceded by any branch" semantics.
 */
internal object OpenCodeTextMateRegexLookbehindSupport {
    fun normalize(pattern: String): String {
        var normalized = pattern
        for ((source, replacement) in rewrites) {
            normalized = normalized.replace(source, replacement)
        }
        return normalized
    }

    private val rewrites =
        listOf(
            "(?<!(?:^|[^$._[:alnum:]])const)" to "(?<!^const)(?<![^$._[:alnum:]]const)",
            "(?<!^let|[^$._[:alnum:]]let|^var|[^$._[:alnum:]]var)" to
                "(?<!^let)(?<![^$._[:alnum:]]let)(?<!^var)(?<![^$._[:alnum:]]var)",
        )
}
