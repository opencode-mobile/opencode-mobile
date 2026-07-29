package dev.opencode.mobile.markdown

/**
 * Versioned set of languages that may enter the TextMate path when the global
 * TextMate preference is enabled. Non-members always use complete semantic
 * fallback even if a grammar is packaged in the manifest.
 */
object TextMateLanguageAllowlist {
    const val ALLOWLIST_VERSION: Int = 1

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
        if (normalized in promotedCanonicalLanguages) return true
        val canonical = manifest?.entryFor(normalized)?.language?.lowercase() ?: return false
        return canonical in promotedCanonicalLanguages
    }
}
