package dev.opencode.mobile.theme

import android.content.Context
import androidx.compose.runtime.getValue
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ThemeManager {
    private const val PREFS_NAME = "opencode_theme"
    private const val KEY_IMPORTED_THEMES = "importedThemes"
    private const val KEY_SELECTED_THEME_ID = "selectedThemeID"
    private const val PRESET_ASSET_DIR = "theme-presets"
    private const val DEFAULT_PRESET_ID = "oc-2"

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

    fun availablePresets(): List<ThemePreset> {
        return ThemeSupport.mergePresets(
            bundled = manifestPresets(),
            imported = importedThemePresets(),
        )
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
            apply()
        }
        currentTheme = loadBundledTheme()
        resolve()
        loadError = null
    }

    fun importTheme(jsonData: String) {
        val previousTheme = currentTheme
        try {
            ThemeSupport.assertImportSize(jsonData.toByteArray(Charsets.UTF_8).size)
            val theme = json.decodeFromString<OpenCodeTheme>(jsonData)
            ThemeSupport.validateTheme(theme)
            ThemeSupport.assertNoPresetIdCollision(theme.id, manifestPresetIds())
            currentTheme = theme
            resolve()
            persistImportedTheme(theme)
            saveSelectedThemeID(theme.id)
            loadError = null
        } catch (e: Exception) {
            currentTheme = previousTheme
            resolve()
            loadError = e.message ?: "Unknown error importing theme"
            DiagnosticLog.error(DiagnosticLogArea.Connection, "Failed to import theme", e)
        }
    }

    fun importThemeFromUri(
        context: Context,
        uri: android.net.Uri,
    ) {
        try {
            val jsonData =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: throw IllegalArgumentException("Could not read theme file")
            importTheme(jsonData)
        } catch (e: Exception) {
            loadError = e.message ?: "Could not read theme file"
            DiagnosticLog.error(DiagnosticLogArea.Connection, "Failed to read theme file", e)
        }
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

    private fun manifestPresetIds(): Set<String> = manifestPresets().map { it.id }.toSet()

    /** Imported preset ids come from user files, so callers must not treat them as safe labels. */
    fun isBundledPreset(id: String): Boolean = id in manifestPresetIds()

    private fun importedThemePresets(): List<ThemePreset> {
        val preferences = prefs() ?: return emptyList()
        val importedThemesJson = preferences.getString(KEY_IMPORTED_THEMES, null) ?: return emptyList()
        val importedThemes =
            try {
                json.decodeFromString<Map<String, String>>(importedThemesJson)
            } catch (e: Exception) {
                DiagnosticLog.warning(DiagnosticLogArea.Connection, "Failed to decode imported themes", e)
                return emptyList()
            }
        return importedThemes.mapNotNull { (_, themeJson) ->
            try {
                val theme = json.decodeFromString<OpenCodeTheme>(themeJson)
                ThemePreset(id = theme.id, name = theme.name)
            } catch (e: Exception) {
                DiagnosticLog.warning(DiagnosticLogArea.Connection, "Failed to decode imported theme preset", e)
                null
            }
        }
    }

    private fun loadActiveTheme(): OpenCodeTheme {
        val defaultTheme = loadBundledTheme()
        val preferences = prefs() ?: return defaultTheme
        val selectedId = preferences.getString(KEY_SELECTED_THEME_ID, null) ?: return defaultTheme
        loadImportedTheme(selectedId, preferences)?.let { return it }
        loadPreset(selectedId)?.let { return it }
        return defaultTheme
    }

    private fun loadImportedTheme(
        selectedId: String,
        preferences: android.content.SharedPreferences,
    ): OpenCodeTheme? {
        val importedThemesJson = preferences.getString(KEY_IMPORTED_THEMES, null) ?: return null
        val importedThemes =
            try {
                json.decodeFromString<Map<String, String>>(importedThemesJson)
            } catch (e: Exception) {
                DiagnosticLog.warning(DiagnosticLogArea.Connection, "Failed to decode imported themes", e)
                return null
            }
        val themeJson = importedThemes[selectedId] ?: return null
        return try {
            json.decodeFromString<OpenCodeTheme>(themeJson)
        } catch (e: Exception) {
            DiagnosticLog.warning(DiagnosticLogArea.Connection, "Failed to restore imported theme $selectedId", e)
            null
        }
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
        } catch (e: Exception) {
            null
        }
    }

    private fun persistImportedTheme(theme: OpenCodeTheme) {
        val preferences = prefs() ?: throw ThemeImportException("Could not access theme storage")
        val existing =
            preferences.getString(KEY_IMPORTED_THEMES, null)?.let { stored ->
                try {
                    json.decodeFromString<MutableMap<String, String>>(stored)
                } catch (_: Exception) {
                    mutableMapOf()
                }
            } ?: mutableMapOf()
        try {
            existing[theme.id] = json.encodeToString(theme)
            preferences.edit().putString(KEY_IMPORTED_THEMES, json.encodeToString(existing)).apply()
        } catch (e: Exception) {
            throw ThemeImportException("Could not save imported theme")
        }
    }

    private fun saveSelectedThemeID(id: String) {
        prefs()?.edit()?.putString(KEY_SELECTED_THEME_ID, id)?.apply()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun createFallbackTheme(): OpenCodeTheme {
        val lightPalette =
            PaletteSeeds(
                neutral = "#f7f7f7",
                ink = "#171311",
                primary = "#dcde8d",
                success = "#12c905",
                warning = "#ffdc17",
                error = "#fc533a",
                info = "#a753ae",
                interactive = "#034cff",
            )
        val darkPalette =
            PaletteSeeds(
                neutral = "#1f1f1f",
                ink = "#f1ece8",
                primary = "#fab283",
                success = "#12c905",
                warning = "#fcd53a",
                error = "#fc533a",
                info = "#edb2f1",
                interactive = "#034cff",
            )
        return OpenCodeTheme(
            name = "OC-2 (Fallback)",
            id = "oc-2-fallback",
            light = ThemeVariant(palette = lightPalette),
            dark = ThemeVariant(palette = darkPalette),
        )
    }
}

class ThemeValidationException(
    message: String,
) : Exception(message)
