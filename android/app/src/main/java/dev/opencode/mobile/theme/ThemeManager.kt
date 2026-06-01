package dev.opencode.mobile.theme

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json

object ThemeManager {
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

    init {
        resolve()
    }

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        currentTheme = loadBundledTheme()
        resolve()
        initialized = true
    }

    fun resolve() {
        resolvedLight = ThemeResolver.resolve(currentTheme.light, isDark = false)
        resolvedDark = ThemeResolver.resolve(currentTheme.dark, isDark = true)
    }

    fun clearAll() {
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
            loadError = null
        } catch (e: Exception) {
            loadError = e.message ?: "Unknown error importing theme"
            Log.e("ThemeManager", "Failed to import theme", e)
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

    private fun loadBundledTheme(): OpenCodeTheme {
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
