package dev.opencode.mobile.markdown

/**
 * Versioned promotion policy for languages that may enter the TextMate path when
 * the global TextMate preference is enabled.
 *
 * v2 defaults every packaged (or named) language on. Callers opt out via the
 * disabled-language override set. When a manifest is supplied, only languages
 * present in that manifest count as promoted so unknown fence tags still use
 * semantic fallback.
 */
object TextMateLanguageAllowlist {
    const val ALLOWLIST_VERSION: Int = 2

    /**
     * Historical top-ten seed. Used only as a UI fallback catalog when the
     * grammar bundle is unavailable; admission no longer depends on this set.
     */
    val promotedCanonicalLanguages: Set<String> =
        setOf(
            "javascript",
            "typescript",
            "python",
            "kotlin",
            "swift",
            "json",
            "yaml",
            "shellscript",
            "rust",
            "go",
        )

    fun isPromoted(
        languageOrAlias: String?,
        manifest: TextMateGrammarManifest? = null,
    ): Boolean {
        val normalized = languageOrAlias?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (manifest == null) {
            // Preference UI and callers without a manifest: default every named language on.
            return true
        }
        return manifest.entryFor(normalized) != null
    }
}
