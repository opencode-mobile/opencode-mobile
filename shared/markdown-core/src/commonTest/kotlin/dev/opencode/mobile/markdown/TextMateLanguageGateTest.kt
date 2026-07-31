package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextMateLanguageGateTest {
    @Test
    fun allowlistOnlyAdmitsPromotedAndRejectsOthers() {
        val gate = TextMateLanguageOverrideGate.AllowlistOnly
        assertTrue(gate.admits("javascript", testManifest()))
        assertTrue(gate.admits("js", testManifest()))
        assertFalse(gate.admits("cpp", testManifest()))
        assertFalse(gate.admits("php", testManifest()))
    }

    @Test
    fun optOutDisablesPromotedLanguage() {
        val gate =
            TextMateLanguageOverrideGate(
                disabledCanonicalLanguages = setOf("python"),
            )
        assertFalse(gate.admits("python", testManifest()))
        assertTrue(gate.admits("javascript", testManifest()))
    }

    @Test
    fun forceEnableAdmitsUnpromotedPackagedLanguage() {
        val gate =
            TextMateLanguageOverrideGate(
                forceEnabledCanonicalLanguages = setOf("cpp", "php"),
            )
        assertTrue(gate.admits("cpp", testManifest()))
        assertTrue(gate.admits("php", testManifest()))
        assertFalse(gate.admits("java", testManifest()))
    }

    @Test
    fun registryUpdateIsVisibleToReaders() {
        TextMateLanguageGateRegistry.resetForTests()
        assertFalse(TextMateLanguageGateRegistry.current().admits("cpp", testManifest()))
        TextMateLanguageGateRegistry.update(
            TextMateLanguageOverrideGate(forceEnabledCanonicalLanguages = setOf("cpp")),
        )
        assertTrue(TextMateLanguageGateRegistry.current().admits("cpp", testManifest()))
        TextMateLanguageGateRegistry.resetForTests()
    }

    private fun testManifest(): TextMateGrammarManifest {
        fun entry(
            language: String,
            aliases: List<String> = emptyList(),
        ) = TextMateGrammarManifestEntry(
            language = language,
            displayName = language,
            aliases = aliases,
            grammarModule = "test",
            grammars =
                listOf(
                    TextMateGrammarDescriptor(
                        name = language,
                        displayName = language,
                        scopeName = "source.$language",
                        resourcePath = "$language.json",
                        embeddedLanguages = emptyList(),
                        aliases = aliases,
                    ),
                ),
        )
        return TextMateGrammarManifest(
            schemaVersion = 1,
            generator = "test",
            shikiVersion = "test",
            languages =
                listOf(
                    entry("javascript", listOf("js")),
                    entry("python"),
                    entry("cpp"),
                    entry("php"),
                    entry("java"),
                ),
        )
    }
}

class TextMateHighlightSpeedTest {
    @Test
    fun bandsUseFenceRelativeBudgets() {
        assertEquals(TextMateHighlightSpeedBand.Fast, TextMateHighlightSpeed.band(10_000_000, 2_000)) // 10ms vs 50
        assertEquals(TextMateHighlightSpeedBand.Ok, TextMateHighlightSpeed.band(40_000_000, 2_000)) // 40ms vs 50
        assertEquals(TextMateHighlightSpeedBand.Slow, TextMateHighlightSpeed.band(80_000_000, 2_000)) // 80ms vs 50
        assertEquals(150.0, TextMateHighlightSpeed.budgetMilliseconds(8_000))
        assertEquals(300.0, TextMateHighlightSpeed.budgetMilliseconds(16_000))
    }

    @Test
    fun onlyMeasuredHighlightOutcomesGetBands() {
        assertEquals(
            TextMateHighlightSpeedBand.Fast,
            TextMateHighlightSpeed.bandForOutcome(TextMateParseOutcome.Highlighted, 1_000_000, 100),
        )
        assertEquals(
            null,
            TextMateHighlightSpeed.bandForOutcome(TextMateParseOutcome.Fallback, 1_000_000, 100),
        )
    }

    @Test
    fun throughputRatesNormalizeByWallTime() {
        // 500 chars in 50 ms → 10 chars/ms; 25 spans → 0.5 spans/ms
        assertEquals(10.0, TextMateHighlightSpeed.charactersPerMillisecond(50_000_000, 500))
        assertEquals(0.5, TextMateHighlightSpeed.spansPerMillisecond(50_000_000, 25))
        assertEquals(
            "10.0 chars/ms · 0.5 spans/ms",
            TextMateHighlightSpeed.throughputLabel(50_000_000, 500, 25),
        )
        assertEquals(null, TextMateHighlightSpeed.throughputLabel(0, 500, 25))
    }
}
