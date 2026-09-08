package dev.opencode.mobile.theme

object ThemeSupport {
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
