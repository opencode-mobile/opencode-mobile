package dev.opencode.mobile.markdown

/**
 * Test helpers for Phase 6 language-gate wiring. Production keeps the allowlist;
 * tokenizer / golden / popular-pack coverage force-enables unpromoted languages.
 */
internal fun <T> withForceEnabledTextMateLanguages(
    vararg languages: String,
    block: () -> T,
): T {
    TextMateLanguageGateRegistry.update(
        TextMateLanguageOverrideGate(
            forceEnabledCanonicalLanguages = languages.map { it.lowercase() }.toSet(),
        ),
    )
    return try {
        block()
    } finally {
        TextMateLanguageGateRegistry.resetForTests()
    }
}

/** Admit every language for suites that exercise TextMate mechanics, not the allowlist. */
internal fun <T> withTextMateGateAdmittingAll(block: () -> T): T {
    TextMateLanguageGateRegistry.update(TextMateLanguageGate { _, _ -> true })
    return try {
        block()
    } finally {
        TextMateLanguageGateRegistry.resetForTests()
    }
}
