package dev.opencode.mobile.theme

import kotlinx.serialization.Serializable

@Serializable
data class ThemePreset(
    val id: String,
    val name: String,
)
