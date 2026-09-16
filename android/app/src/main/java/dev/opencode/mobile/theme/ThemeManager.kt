package dev.opencode.mobile.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import kotlinx.serialization.json.Json

object ThemeManager {
    private const val PREFS_NAME = "opencode_theme"
    private const val KEY_IMPORTED_THEMES = "importedThemes"
    private const val KEY_SELECTED_THEME_ID = "selectedThemeID"
    private const val KEY_SELECTED_SYNTAX_ID = "selectedSyntaxID"
    private const val PRESET_ASSET_DIR = "theme-presets"
    private const val SYNTAX_ASSET_DIR = "syntax-presets"
    private const val DEFAULT_PRESET_ID = "porcelain"
    const val MATCH_SYNTAX_PRESET_ID = SyntaxHighlightPreset.MATCH_SYNTAX_PRESET_ID

    private val json = Json { ignoreUnknownKeys = true }

    var currentTheme: OpenCodeTheme by mutableStateOf(createFallbackTheme())
        private set

    var currentSyntaxPreset: SyntaxHighlightPreset by mutableStateOf(SyntaxHighlightPreset.Match)
        private set

    var resolvedLight: ResolvedTokens by mutableStateOf(ResolvedTokens())
        private set

    var resolvedDark: ResolvedTokens by mutableStateOf(ResolvedTokens())
        private set

    var loadError: String? by mutableStateOf(null)
        private set

    private var appContext: Context? = null
    private var initialized = false
    private var cachedManifestPresets: List<ThemePreset>? = null
    private var cachedSyntaxPresets: List<SyntaxHighlightPreset>? = null

    init {
        resolve()
    }

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        currentTheme = loadActiveTheme()
        currentSyntaxPreset = loadActiveSyntaxPreset()
        resolve()
        initialized = true
    }

    fun resolve() {
        val light = ThemeResolver.resolve(currentTheme.light, isDark = false)
        val dark = ThemeResolver.resolve(currentTheme.dark, isDark = true)
        resolvedLight = ThemeResolver.overlaySyntax(light, currentSyntaxPreset.light)
        resolvedDark = ThemeResolver.overlaySyntax(dark, currentSyntaxPreset.dark)
    }

    fun availablePresets(): List<ThemePreset> = manifestPresets()

    fun availableSyntaxPresets(): List<SyntaxHighlightPreset> = syntaxPresets()

    fun resolvedPreview(themeId: String): Pair<ResolvedTokens, ResolvedTokens>? {
        val theme = loadPreset(themeId) ?: return null
        return ThemeResolver.resolve(theme.light, isDark = false) to
            ThemeResolver.resolve(theme.dark, isDark = true)
    }

    fun syntaxTokensForPreview(
        preset: SyntaxHighlightPreset,
        isDark: Boolean,
    ): Map<String, String> {
        if (preset.isMatch) {
            val themeTokens =
                ThemeResolver.resolve(
                    if (isDark) currentTheme.dark else currentTheme.light,
                    isDark = isDark,
                )
            return themeTokens.tokens.filterKeys { it.startsWith("syntax-") }
        }
        return if (isDark) preset.dark else preset.light
    }

    fun applySyntaxPreset(id: String) {
        val preset = syntaxPresets().firstOrNull { it.id == id }
        if (preset == null) {
            loadError = "Syntax highlight preset not found"
            return
        }
        currentSyntaxPreset = preset
        resolve()
        saveSelectedSyntaxID(id)
        loadError = null
    }

    fun applyPreset(id: String) {
        val theme = loadPreset(id)
        if (theme == null) {
            loadError = "Theme preset not found"
            return
        }
        try {
            ThemeSupport.validateTheme(theme)
            currentTheme = theme
            resolve()
            saveSelectedThemeID(id)
            loadError = null
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to apply theme preset"
            DiagnosticLog.error(DiagnosticLogArea.Connection, "Failed to apply preset $id", e)
        }
    }

    fun clearAll() {
        prefs()?.edit()?.apply {
            remove(KEY_IMPORTED_THEMES)
            remove(KEY_SELECTED_THEME_ID)
            remove(KEY_SELECTED_SYNTAX_ID)
            apply()
        }
        currentTheme = loadBundledTheme()
        currentSyntaxPreset = SyntaxHighlightPreset.Match
        resolve()
        loadError = null
    }

    private fun manifestPresets(): List<ThemePreset> {
        cachedManifestPresets?.let { return it }
        val ctx = appContext ?: return emptyList()
        return try {
            val manifest =
                ctx.assets
                    .open("$PRESET_ASSET_DIR/manifest.json")
                    .bufferedReader()
                    .use { it.readText() }
            json.decodeFromString<List<ThemePreset>>(manifest).also { cachedManifestPresets = it }
        } catch (e: Exception) {
            DiagnosticLog.error(DiagnosticLogArea.Connection, "Failed to load theme preset manifest", e)
            emptyList()
        }
    }

    private fun syntaxPresets(): List<SyntaxHighlightPreset> {
        cachedSyntaxPresets?.let { return it }
        val ctx = appContext ?: return listOf(SyntaxHighlightPreset.Match)
        return try {
            val manifest =
                ctx.assets
                    .open("$SYNTAX_ASSET_DIR/syntax-manifest.json")
                    .bufferedReader()
                    .use { it.readText() }
            json
                .decodeFromString<List<SyntaxHighlightPreset>>(manifest)
                .map { entry ->
                    if (entry.id == MATCH_SYNTAX_PRESET_ID) {
                        SyntaxHighlightPreset.Match.copy(name = entry.name, blurb = entry.blurb)
                    } else {
                        loadSyntaxPreset(entry.id) ?: entry
                    }
                }.also { cachedSyntaxPresets = it }
        } catch (e: Exception) {
            DiagnosticLog.error(DiagnosticLogArea.Connection, "Failed to load syntax preset manifest", e)
            listOf(SyntaxHighlightPreset.Match)
        }
    }

    private fun loadSyntaxPreset(id: String): SyntaxHighlightPreset? {
        val ctx = appContext ?: return null
        return try {
            val jsonString =
                ctx.assets
                    .open("$SYNTAX_ASSET_DIR/$id.json")
                    .bufferedReader()
                    .use { it.readText() }
            json.decodeFromString<SyntaxHighlightPreset>(jsonString)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadActiveSyntaxPreset(): SyntaxHighlightPreset {
        val selectedId = prefs()?.getString(KEY_SELECTED_SYNTAX_ID, null) ?: return SyntaxHighlightPreset.Match
        return syntaxPresets().firstOrNull { it.id == selectedId } ?: SyntaxHighlightPreset.Match
    }

    private fun saveSelectedSyntaxID(id: String) {
        prefs()?.edit()?.putString(KEY_SELECTED_SYNTAX_ID, id)?.apply()
    }

    private fun loadActiveTheme(): OpenCodeTheme {
        val defaultTheme = loadBundledTheme()
        val selectedId = prefs()?.getString(KEY_SELECTED_THEME_ID, null) ?: return defaultTheme
        return loadPreset(selectedId) ?: defaultTheme
    }

    private fun loadBundledTheme(): OpenCodeTheme {
        loadPreset(DEFAULT_PRESET_ID)?.let { return it }
        if (appContext == null) {
            DiagnosticLog.warning(DiagnosticLogArea.Connection, "App context not set, using fallback theme")
        } else {
            DiagnosticLog.error(DiagnosticLogArea.Connection, "Failed to load bundled preset $DEFAULT_PRESET_ID")
        }
        return createFallbackTheme()
    }

    private fun loadPreset(id: String): OpenCodeTheme? {
        val ctx = appContext ?: return null
        return try {
            val jsonString =
                ctx.assets
                    .open("$PRESET_ASSET_DIR/$id.json")
                    .bufferedReader()
                    .use { it.readText() }
            json.decodeFromString<OpenCodeTheme>(jsonString)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveSelectedThemeID(id: String) {
        prefs()?.edit()?.putString(KEY_SELECTED_THEME_ID, id)?.apply()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun createFallbackTheme(): OpenCodeTheme {
        val lightPalette =
            PaletteSeeds(
                neutral = "#F8F8F8",
                ink = "#171717",
                primary = "#1456F7",
                success = "#089B00",
                warning = "#C78B05",
                error = "#CB321C",
                info = "#5A5A8A",
                interactive = "#1456F7",
                diffAdd = "#089B00",
                diffDelete = "#CB321C",
            )
        val darkPalette =
            PaletteSeeds(
                neutral = "#121212",
                ink = "#EDEDED",
                primary = "#8BA8FF",
                success = "#4EC97A",
                warning = "#E0B04A",
                error = "#E85A5A",
                info = "#B4A6E0",
                interactive = "#8BA8FF",
                diffAdd = "#7AE0A0",
                diffDelete = "#E85A5A",
            )
        return OpenCodeTheme(
            name = "Porcelain",
            id = "porcelain-fallback",
            light = ThemeVariant(palette = lightPalette),
            dark = ThemeVariant(palette = darkPalette),
        )
    }
}

class ThemeValidationException(
    message: String,
) : Exception(message)
