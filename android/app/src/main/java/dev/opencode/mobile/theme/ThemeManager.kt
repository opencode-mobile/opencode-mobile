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
    private const val PRESET_ASSET_DIR = "theme-presets"
    private const val DEFAULT_PRESET_ID = "graphite"

    private val json = Json { ignoreUnknownKeys = true }

    var currentTheme: OpenCodeTheme by mutableStateOf(createFallbackTheme())
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

    init {
        resolve()
    }

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        currentTheme = loadActiveTheme()
        resolve()
        initialized = true
    }

    fun resolve() {
        resolvedLight = ThemeResolver.resolve(currentTheme.light, isDark = false)
        resolvedDark = ThemeResolver.resolve(currentTheme.dark, isDark = true)
    }

    fun availablePresets(): List<ThemePreset> = manifestPresets()

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
            apply()
        }
        currentTheme = loadBundledTheme()
        resolve()
        loadError = null
    }

    private fun manifestPresets(): List<ThemePreset> {
        cachedManifestPresets?.let { return it }
        val ctx = appContext ?: return emptyList()
        return try {
            val manifest =
                ctx.assets.open("$PRESET_ASSET_DIR/manifest.json").bufferedReader().use { it.readText() }
            json.decodeFromString<List<ThemePreset>>(manifest).also { cachedManifestPresets = it }
        } catch (e: Exception) {
            DiagnosticLog.error(DiagnosticLogArea.Connection, "Failed to load theme preset manifest", e)
            emptyList()
        }
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
                ctx.assets.open("$PRESET_ASSET_DIR/$id.json").bufferedReader().use { it.readText() }
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
                neutral = "#F3F4F6",
                ink = "#14161A",
                primary = "#3B4FD9",
                success = "#1B8A4A",
                warning = "#C48412",
                error = "#D23B3B",
                info = "#6B5CAD",
                interactive = "#3B4FD9",
                diffAdd = "#1B8A4A",
                diffDelete = "#D23B3B",
            )
        val darkPalette =
            PaletteSeeds(
                neutral = "#141518",
                ink = "#ECEEF1",
                primary = "#8B97F0",
                success = "#4EC97A",
                warning = "#E0B04A",
                error = "#E85A5A",
                info = "#B4A6E0",
                interactive = "#8B97F0",
                diffAdd = "#7AE0A0",
                diffDelete = "#E85A5A",
            )
        return OpenCodeTheme(
            name = "Graphite",
            id = "graphite-fallback",
            light = ThemeVariant(palette = lightPalette),
            dark = ThemeVariant(palette = darkPalette),
        )
    }
}

class ThemeValidationException(
    message: String,
) : Exception(message)
