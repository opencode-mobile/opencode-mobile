package dev.opencode.mobile.ui.sessionchat.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class DictationLanguagePackTest {
    // Pack list reported by Android System Intelligence on a Galaxy A15 (Oct 2026).
    private val galaxyPacks =
        listOf("en-AU", "en-CA", "en-GB", "en-IE", "en-IN", "en-SG", "en-US", "de-AT", "de-CH", "de-DE", "fr-FR", "pt-BR")
    private val likely =
        mapOf("en" to "en-US", "de" to "de-DE", "fr" to "fr-FR", "pt" to "pt-BR")

    private fun match(
        packs: List<String>,
        tag: String,
    ) = matchingLanguagePack(packs, Locale.forLanguageTag(tag)) { likely[it] }

    @Test
    fun exactPack_wins() {
        assertEquals("en-GB", match(galaxyPacks, "en-GB"))
        assertEquals("de-CH", match(galaxyPacks, "de-CH"))
    }

    @Test
    fun regionWithoutPack_usesTheLikelyRegion() {
        assertEquals("en-US", match(galaxyPacks, "en-DE"))
        assertEquals("de-DE", match(galaxyPacks, "de-LU"))
        assertEquals("pt-BR", match(galaxyPacks, "pt-PT"))
    }

    @Test
    fun noLikelyRegionPack_usesFirstSameLanguagePack() {
        assertEquals("en-GB", match(listOf("en-GB", "en-AU"), "en-DE"))
        assertEquals("en_GB", match(listOf("en_GB"), "en-DE"))
    }

    @Test
    fun otherLanguage_hasNoPack() {
        assertNull(match(galaxyPacks, "nl-NL"))
        assertNull(match(emptyList(), "en-US"))
    }

    @Test
    fun support_requestsTheMatchedPack() {
        val locale = Locale.forLanguageTag("en-DE")
        assertEquals(
            DictationSupport.ModelNotInstalled("en-US"),
            dictationSupportFor(emptyList(), emptyList(), galaxyPacks, locale, hasPermission = true) { likely[it] },
        )
        assertEquals(
            DictationSupport.ModelDownloading("en-US"),
            dictationSupportFor(emptyList(), listOf("en-US"), galaxyPacks, locale, hasPermission = true) { likely[it] },
        )
        assertEquals(
            "en-US",
            dictationSupportFor(listOf("en-US"), emptyList(), galaxyPacks, locale, hasPermission = true) { likely[it] }
                .languageTag,
        )
        assertEquals(
            DictationSupport.PermissionDenied,
            dictationSupportFor(listOf("en-US"), emptyList(), galaxyPacks, locale, hasPermission = false) { likely[it] },
        )
        assertEquals(
            DictationSupport.Hidden,
            dictationSupportFor(emptyList(), emptyList(), listOf("ja-JP"), locale, hasPermission = true) { likely[it] },
        )
    }
}
