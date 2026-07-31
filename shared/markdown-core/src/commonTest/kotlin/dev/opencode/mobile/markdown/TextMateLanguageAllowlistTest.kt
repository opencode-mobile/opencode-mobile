package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextMateLanguageAllowlistTest {
    @Test
    fun promotesTopTenCanonicalNames() {
        TextMateLanguageAllowlist.promotedCanonicalLanguages.forEach { language ->
            assertTrue(TextMateLanguageAllowlist.isPromoted(language), language)
        }
        assertTrue(TextMateLanguageAllowlist.ALLOWLIST_VERSION >= 1)
    }

    @Test
    fun resolvesAliasesThroughManifest() {
        val descriptor =
            TextMateGrammarDescriptor(
                name = "javascript",
                displayName = "JavaScript",
                scopeName = "source.js",
                resourcePath = "javascript.json",
                embeddedLanguages = emptyList(),
                aliases = listOf("js"),
            )
        val manifest =
            TextMateGrammarManifest(
                schemaVersion = 1,
                generator = "test",
                shikiVersion = "test",
                languages =
                    listOf(
                        TextMateGrammarManifestEntry(
                            language = "javascript",
                            displayName = "JavaScript",
                            aliases = listOf("js"),
                            grammarModule = "test",
                            grammars = listOf(descriptor),
                        ),
                    ),
            )
        assertTrue(TextMateLanguageAllowlist.isPromoted("js", manifest))
        assertTrue(TextMateLanguageAllowlist.isPromoted("JavaScript", manifest))
        assertFalse(TextMateLanguageAllowlist.isPromoted("java", manifest))
        assertFalse(TextMateLanguageAllowlist.isPromoted(null, manifest))
        assertFalse(TextMateLanguageAllowlist.isPromoted("", manifest))
    }
}
