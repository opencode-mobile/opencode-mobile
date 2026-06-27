package dev.opencode.mobile.theme

object ThemeSupport {
    const val MAX_IMPORT_BYTES: Int = 512 * 1024

    fun mergePresets(
        bundled: List<ThemePreset>,
        imported: List<ThemePreset>,
    ): List<ThemePreset> {
        val bundledIds = bundled.map { it.id }.toSet()
        val extra =
            imported
                .filter { it.id !in bundledIds }
                .sortedBy { it.name.lowercase() }
        return bundled + extra
    }

    fun assertImportSize(byteCount: Int) {
        if (byteCount > MAX_IMPORT_BYTES) {
            throw ThemeImportException(
                "Theme file is too large (max ${MAX_IMPORT_BYTES / 1024} KB)",
            )
        }
    }

    fun assertNoPresetIdCollision(
        themeId: String,
        bundledPresetIds: Set<String>,
    ) {
        if (themeId in bundledPresetIds) {
            throw ThemeImportException(
                "Theme ID \"$themeId\" conflicts with a bundled preset",
            )
        }
    }

    fun validateTheme(theme: OpenCodeTheme) {
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
                throw ThemeValidationException(
                    "Missing required palette seeds: ${missing.joinToString(", ")}",
                )
            }
        }
    }
}

class ThemeImportException(
    message: String,
) : Exception(message)
