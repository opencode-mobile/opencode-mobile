package dev.opencode.mobile.theme

import kotlinx.serialization.Serializable

@Serializable
data class ThemePreset(
    val id: String,
    val name: String,
    val blurb: String = "",
    /** The apps that offer this preset, by [ThemeManager.appId]; null means every app. */
    val apps: List<String>? = null,
)
