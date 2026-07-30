package dev.opencode.mobile.markdown

/**
 * Joni rejects some TextMate negative lookbehinds that alternate zero-width `^`
 * with fixed-width branches. Rewrite them into chained fixed-width lookbehinds
 * with equivalent "must not be preceded by any branch" semantics.
 */
internal object OpenCodeTextMateRegexLookbehindSupport {
    fun normalize(pattern: String): String {
        // Bundled TextMate patterns rarely hit these rewrites; skip scanning when
        // the pattern has no negative lookbehind at all.
        if (!pattern.contains("(?<!")) return pattern
        var normalized = pattern
        for ((source, replacement) in rewrites) {
            if (normalized.contains(source)) {
                normalized = normalized.replace(source, replacement)
            }
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
