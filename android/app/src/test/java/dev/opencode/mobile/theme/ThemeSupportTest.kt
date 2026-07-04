package dev.opencode.mobile.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class ThemeSupportTest {
  @Test
  fun mergePresets_appendsImportedThemesNotInManifest() {
    val bundled =
      listOf(
        ThemePreset(id = "oc-2", name = "OC-2"),
        ThemePreset(id = "dracula", name = "Dracula"),
      )
    val imported =
      listOf(
        ThemePreset(id = "my-theme", name = "My Theme"),
        ThemePreset(id = "dracula", name = "Duplicate Dracula"),
      )

    val merged = ThemeSupport.mergePresets(bundled, imported)

    assertEquals(listOf("oc-2", "dracula", "my-theme"), merged.map { it.id })
  }

  @Test
  fun assertNoPresetIdCollision_rejectsBundledIds() {
    val error =
      assertThrows(ThemeImportException::class.java) {
        ThemeSupport.assertNoPresetIdCollision("dracula", setOf("oc-2", "dracula"))
      }
    assertTrue(error.message!!.contains("conflicts with a bundled preset"))
  }

  @Test
  fun assertImportSize_rejectsOversizedPayload() {
    val error =
      assertThrows(ThemeImportException::class.java) {
        ThemeSupport.assertImportSize(ThemeSupport.MAX_IMPORT_BYTES + 1)
      }
    assertTrue(error.message!!.contains("too large"))
  }

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
