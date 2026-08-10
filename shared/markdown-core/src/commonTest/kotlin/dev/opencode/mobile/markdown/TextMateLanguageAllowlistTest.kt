package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextMateLanguageAllowlistTest {
    @Test
    fun promotesEveryNamedLanguageWithoutManifest() {
        TextMateLanguageAllowlist.promotedCanonicalLanguages.forEach { language ->
            assertTrue(TextMateLanguageAllowlist.isPromoted(language), language)
        }
        assertTrue(TextMateLanguageAllowlist.isPromoted("java"))
        assertTrue(TextMateLanguageAllowlist.isPromoted("cpp"))
        assertTrue(TextMateLanguageAllowlist.ALLOWLIST_VERSION >= 2)
        assertFalse(TextMateLanguageAllowlist.isPromoted(null))
        assertFalse(TextMateLanguageAllowlist.isPromoted(""))
    }

    @Test
    fun promotesOnlyManifestMembersWhenManifestProvided() {
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
        assertTrue(TextMateLanguageAllowlist.isPromoted("javascript", manifest))
        assertFalse(TextMateLanguageAllowlist.isPromoted("java", manifest))
        assertFalse(TextMateLanguageAllowlist.isPromoted(null, manifest))
        assertFalse(TextMateLanguageAllowlist.isPromoted("", manifest))
    }
}
