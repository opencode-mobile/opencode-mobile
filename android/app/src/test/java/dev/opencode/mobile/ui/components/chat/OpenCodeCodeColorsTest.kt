package dev.opencode.mobile.ui.components

import androidx.compose.ui.graphics.Color
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.SyntaxHighlightPreset
import dev.opencode.mobile.theme.ThemeResolver
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenCodeCodeColorsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun tableMapsEveryRoleToItsToken() {
        val expected =
            mapOf(
                CodeTokenRole.Keyword to "syntax-keyword",
                CodeTokenRole.String to "syntax-string",
                CodeTokenRole.Number to "syntax-number",
                CodeTokenRole.Constant to "syntax-constant",
                CodeTokenRole.Comment to "syntax-comment",
                CodeTokenRole.Type to "syntax-type",
                CodeTokenRole.Function to "syntax-function",
                CodeTokenRole.Property to "syntax-property",
                CodeTokenRole.Attribute to "syntax-attribute",
                CodeTokenRole.Tag to "syntax-keyword",
                CodeTokenRole.Inserted to "diff-add",
                CodeTokenRole.Deleted to "diff-delete",
                CodeTokenRole.Warning to "syntax-diff-unknown",
                CodeTokenRole.Critical to "syntax-critical",
                CodeTokenRole.Variable to null,
                CodeTokenRole.Operator to null,
                CodeTokenRole.Punctuation to null,
                CodeTokenRole.Plain to null,
            )

        assertEquals(CodeTokenRole.entries.toSet(), expected.keys)
        CodeTokenRole.entries.forEach { role ->
            assertEquals("$role", expected.getValue(role), CodeRoleTokens.tokenFor(role))
        }
    }

    @Test
    fun colorsFollowTheTableAndPlainRolesUseCodeText() {
        val tokens = distinctTokens()
        val colors = CodeColors.from(tokens)

        CodeTokenRole.entries.forEach { role ->
            val expected = CodeRoleTokens.tokenFor(role)?.let { ThemeResolver.parseHexColor(tokens.getValue(it)) } ?: colors.plain
            assertEquals("$role", expected, colors.colorFor(role))
        }
        listOf(CodeTokenRole.Variable, CodeTokenRole.Operator, CodeTokenRole.Punctuation, CodeTokenRole.Plain).forEach { role ->
            assertEquals("$role", ThemeResolver.parseHexColor(tokens.getValue("text-strong")), colors.colorFor(role))
        }
    }

    @Test
    fun codeSurfaceFallsBackToTheUiThemeWithoutPresetBackground() {
        val colors = CodeColors.from(distinctTokens())
        val fallback = Color(0xFF123456)

        assertNull(colors.background)
        assertEquals(fallback, colors.surface(fallback))
        assertEquals(ThemeResolver.parseHexColor("#101010"), colors.plain)
    }

    @Test
    fun codeSurfaceUsesPresetBackgroundAndForeground() {
        val colors =
            CodeColors.from(
                distinctTokens() + mapOf("syntax-background" to "#0D1117", "syntax-foreground" to "#E6EDF3"),
            )

        assertEquals(ThemeResolver.parseHexColor("#0D1117"), colors.background)
        assertEquals(ThemeResolver.parseHexColor("#0D1117"), colors.surface(Color(0xFF123456)))
        assertEquals(ThemeResolver.parseHexColor("#E6EDF3"), colors.plain)
        assertEquals(colors.plain, colors.colorFor(CodeTokenRole.Variable))
    }

    @Test
    fun bundledPresetsResolveEveryTableTokenOverEveryTheme() {
        val themes =
            assetFiles("theme-presets")
                .filter { it.name != "manifest.json" }
                .map { json.decodeFromString<OpenCodeTheme>(it.readText()) }
        val presets =
            assetFiles("syntax-presets")
                .filter { it.name != "syntax-manifest.json" }
                .map { json.decodeFromString<SyntaxHighlightPreset>(it.readText()) } + SyntaxHighlightPreset.Match
        val tableTokens = CodeTokenRole.entries.mapNotNull(CodeRoleTokens::tokenFor).toSet()
        assertTrue(themes.isNotEmpty())
        assertTrue(presets.size > 1)

        themes.forEach { theme ->
            presets.forEach { preset ->
                listOf(false, true).forEach { isDark ->
                    val variant = if (isDark) theme.dark else theme.light
                    val syntax = if (isDark) preset.dark else preset.light
                    val tokens = ThemeResolver.overlaySyntax(ThemeResolver.resolve(variant, isDark), syntax).tokens
                    val label = "${theme.id}/${preset.id}/${if (isDark) "dark" else "light"}"
                    tableTokens.forEach { token -> assertNotNull("$label $token", tokens[token]) }
                    assertEquals("$label background and foreground", "syntax-background" in tokens, "syntax-foreground" in tokens)
                    if (preset.isMatch) assertNull("$label background", tokens["syntax-background"])
                }
            }
        }
    }

    private fun assetFiles(directory: String): List<File> =
        File("src/main/assets/$directory").listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }

    /** Every table token with its own color, plus the UI theme's code text. */
    private fun distinctTokens(): Map<String, String> {
        val tableTokens = CodeTokenRole.entries.mapNotNull(CodeRoleTokens::tokenFor).distinct()
        val colors =
            tableTokens
                .mapIndexed { index, token ->
                    token to "#%02X%02X%02X".format(0x20 + index * 8, 0x80, 0xC0 - index * 4)
                }.toMap()
        return colors + ("text-strong" to "#101010")
    }
}
