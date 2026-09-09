package dev.opencode.mobile.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSupportTest {
    @Test
    fun validateTheme_rejectsMissingPaletteSeeds() {
        val theme =
            OpenCodeTheme(
                name = "Broken",
                id = "broken",
                light = ThemeVariant(palette = incompletePalette()),
                dark = ThemeVariant(palette = incompletePalette()),
            )

        val error =
            assertThrows(ThemeValidationException::class.java) {
                ThemeSupport.validateTheme(theme)
            }
        assertTrue(error.message!!.contains("Missing required palette seeds"))
    }

    @Test
    fun validateTheme_acceptsCompletePalette() {
        ThemeSupport.validateTheme(sampleTheme(id = "valid"))
    }

    @Test
    fun resolverProvidesPathASyntaxFallbackTokens() {
        val theme = sampleTheme(id = "path-a")

        val light = ThemeResolver.resolve(theme.light, isDark = false)
        val dark = ThemeResolver.resolve(theme.dark, isDark = true)

        assertEquals("#D1383D", light["syntax-variable"])
        assertEquals("#E06C75", dark["syntax-variable"])
        assertEquals("#318795", light["syntax-operator"])
        assertEquals("#56B6C2", dark["syntax-operator"])
        assertEquals(light["text-strong"], light["syntax-punctuation"])
        assertEquals(dark["text-strong"], dark["syntax-punctuation"])
    }

    @Test
    fun resolverProvidesDesktopAlignedInlineCodeBackground() {
        val theme = sampleTheme(id = "inline-code")

        val light = ThemeResolver.resolve(theme.light, isDark = false)
        val dark = ThemeResolver.resolve(theme.dark, isDark = true)

        assertEquals("#00000014", light["markdown-code-bg"])
        assertEquals("#FFFFFF14", dark["markdown-code-bg"])
        assertEquals(light["text-strong"], light["markdown-code-text"])
        assertEquals(dark["text-strong"], dark["markdown-code-text"])
    }

    @Test
    fun resolverUsesNeutralChromeForLinksAndQuotes() {
        val theme = sampleTheme(id = "link-quote")

        val light = ThemeResolver.resolve(theme.light, isDark = false)
        val dark = ThemeResolver.resolve(theme.dark, isDark = true)

        assertEquals(light["text-strong"], light["markdown-link-text"])
        assertEquals(dark["text-strong"], dark["markdown-link-text"])
        assertEquals(light["text-strong"], light["markdown-link-underline"])
        assertEquals(dark["text-strong"], dark["markdown-link-underline"])
        assertTrue(light["markdown-link-text"] != light["interactive"])
        assertTrue(dark["markdown-link-text"] != dark["interactive"])
        assertEquals(light["border-weak-base"], light["markdown-quote-bar"])
        assertEquals(dark["border-weak-base"], dark["markdown-quote-bar"])
        assertTrue(light["markdown-quote-bar"] != light["interactive"])
        assertTrue(dark["markdown-quote-bar"] != dark["interactive"])
    }

    @Test
    fun overlaySyntax_replacesSyntaxKeysAndIgnoresOthers() {
        val base = ThemeResolver.resolve(sampleTheme(id = "overlay").light, isDark = false)
        val overlaid =
            ThemeResolver.overlaySyntax(
                base,
                mapOf(
                    "syntax-keyword" to "#AABBCC",
                    "background-base" to "#000000",
                    "syntax-comment" to "112233",
                ),
            )

        assertEquals("#AABBCC", overlaid["syntax-keyword"])
        assertEquals("#112233", overlaid["syntax-comment"])
        assertEquals(base["background-base"], overlaid["background-base"])
        assertEquals(base["text-strong"], overlaid["text-strong"])
    }

    private fun incompletePalette(): PaletteSeeds =
        PaletteSeeds(
            neutral = "#111111",
            ink = "",
            primary = "#222222",
            success = "#333333",
            warning = "#444444",
            error = "#555555",
            info = "#666666",
        )

    private fun sampleTheme(id: String): OpenCodeTheme {
        val palette =
            PaletteSeeds(
                neutral = "#111111",
                ink = "#222222",
                primary = "#333333",
                success = "#444444",
                warning = "#555555",
                error = "#666666",
                info = "#777777",
            )
        return OpenCodeTheme(
            name = "Sample",
            id = id,
            light = ThemeVariant(palette = palette),
            dark = ThemeVariant(palette = palette),
        )
    }
}
