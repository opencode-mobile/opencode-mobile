package dev.opencode.mobile.markdown

/**
 * Decides whether a fence language may enter the TextMate path when the global
 * TextMate preference is on.
 */
fun interface TextMateLanguageGate {
    fun admits(
        languageOrAlias: String?,
        manifest: TextMateGrammarManifest?,
    ): Boolean
}

/**
 * Immutable A+B gate: promoted languages default on (unless opted out);
 * unpromoted languages default off (unless force-enabled). Allowlist v2 promotes
 * every packaged language, so force-enable is mainly for legacy overrides.
 */
data class TextMateLanguageOverrideGate(
    val disabledCanonicalLanguages: Set<String> = emptySet(),
    val forceEnabledCanonicalLanguages: Set<String> = emptySet(),
) : TextMateLanguageGate {
    override fun admits(
        languageOrAlias: String?,
        manifest: TextMateGrammarManifest?,
    ): Boolean {
        val canonical = canonicalLanguage(languageOrAlias, manifest) ?: return false
        return if (TextMateLanguageAllowlist.isPromoted(canonical, manifest)) {
            canonical !in disabledCanonicalLanguages
        } else {
            canonical in forceEnabledCanonicalLanguages
        }
    }

    companion object {
        val AllowlistOnly: TextMateLanguageOverrideGate = TextMateLanguageOverrideGate()

        fun canonicalLanguage(
            languageOrAlias: String?,
            manifest: TextMateGrammarManifest?,
        ): String? {
            val normalized = languageOrAlias?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return manifest?.entryFor(normalized)?.language?.lowercase() ?: normalized
        }
    }
}

/**
 * Factory helpers for Swift/ObjC callers that cannot easily construct Kotlin sets.
 */
object TextMateLanguageOverrideGates {
    fun allowlistOnly(): TextMateLanguageOverrideGate = TextMateLanguageOverrideGate.AllowlistOnly

    fun create(
        disabledCanonicalLanguages: List<String>,
        forceEnabledCanonicalLanguages: List<String>,
    ): TextMateLanguageOverrideGate =
        TextMateLanguageOverrideGate(
            disabledCanonicalLanguages = disabledCanonicalLanguages.map { it.lowercase() }.toSet(),
            forceEnabledCanonicalLanguages = forceEnabledCanonicalLanguages.map { it.lowercase() }.toSet(),
        )
}

/**
 * Process-local gate used by [OpenCodeSyntaxHighlighter]. Platform preferences
 * publish a new immutable snapshot when Developer Tools language toggles change.
 * Assignment of the immutable [TextMateLanguageGate] reference is sufficient;
 * the highlight owner is single-lane on both platforms.
 */
object TextMateLanguageGateRegistry {
    private var gate: TextMateLanguageGate = TextMateLanguageOverrideGate.AllowlistOnly

    fun current(): TextMateLanguageGate = gate

    fun update(next: TextMateLanguageGate) {
        gate = next
    }

    fun resetForTests() {
        update(TextMateLanguageOverrideGate.AllowlistOnly)
    }
}
