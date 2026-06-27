package dev.opencode.mobile.theme

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
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
    private var cachedPresets: List<ThemePreset>? = null

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
        cachedPresets?.let { return it }
        val ctx = appContext ?: return emptyList()
        return try {
            val manifest =
                ctx.assets.open("$PRESET_ASSET_DIR/manifest.json").bufferedReader().use { it.readText() }
            json.decodeFromString<List<ThemePreset>>(manifest).also { cachedPresets = it }
        } catch (e: Exception) {
            Log.e("ThemeManager", "Failed to load theme preset manifest", e)
            emptyList()
        }
    }

    fun applyPreset(id: String) {
        val theme = loadPreset(id)
        if (theme == null) {
            loadError = "Theme preset not found"
            return
        }
        try {
            validate(theme)
            currentTheme = theme
            resolve()
            saveSelectedThemeID(id)
            loadError = null
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to apply theme preset"
            Log.e("ThemeManager", "Failed to apply preset $id", e)
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
        try {
            val theme = json.decodeFromString<OpenCodeTheme>(jsonData)
            validate(theme)
            currentTheme = theme
            resolve()
            persistImportedTheme(theme)
            saveSelectedThemeID(theme.id)
            loadError = null
        } catch (e: Exception) {
            loadError = e.message ?: "Unknown error importing theme"
            Log.e("ThemeManager", "Failed to import theme", e)
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
            Log.e("ThemeManager", "Failed to read theme file", e)
        }
    }

    private fun validate(theme: OpenCodeTheme) {
        val required = listOf("neutral", "ink", "primary", "success", "warning", "error", "info")
        for (variant in listOf(theme.light, theme.dark)) {
            val missing =
                required.filter { key ->
                    val value =
                        when (key) {
                            "neutral" -> variant.palette.neutral
                            "ink" -> variant.palette.ink
                            "primary" -> variant.palette.primary
                            "success" -> variant.palette.success
                            "warning" -> variant.palette.warning
                            "error" -> variant.palette.error
                            "info" -> variant.palette.info
                            else -> return@filter true
                        }
                    value.isBlank()
                }
            if (missing.isNotEmpty()) {
                throw ThemeValidationException("Missing required palette seeds: ${missing.joinToString(", ")}")
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
                Log.w("ThemeManager", "Failed to decode imported themes", e)
                return null
            }
        val themeJson = importedThemes[selectedId] ?: return null
        return try {
            json.decodeFromString<OpenCodeTheme>(themeJson)
        } catch (e: Exception) {
            Log.w("ThemeManager", "Failed to restore imported theme $selectedId", e)
            null
        }
    }

    private fun loadBundledTheme(): OpenCodeTheme {
        loadPreset(DEFAULT_PRESET_ID)?.let { return it }
        val ctx = appContext
        if (ctx == null) {
            Log.w("ThemeManager", "App context not set, using fallback theme")
            return createFallbackTheme()
        }
        return try {
            val inputStream = ctx.assets.open("oc-2.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<OpenCodeTheme>(jsonString)
        } catch (e: Exception) {
            Log.e("ThemeManager", "Failed to load bundled oc-2.json", e)
            createFallbackTheme()
        }
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
        val preferences = prefs() ?: return
        val existing =
            preferences.getString(KEY_IMPORTED_THEMES, null)?.let { stored ->
                try {
                    json.decodeFromString<MutableMap<String, String>>(stored)
                } catch (_: Exception) {
                    mutableMapOf()
                }
            } ?: mutableMapOf()
        existing[theme.id] = json.encodeToString(theme)
        preferences.edit().putString(KEY_IMPORTED_THEMES, json.encodeToString(existing)).apply()
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
