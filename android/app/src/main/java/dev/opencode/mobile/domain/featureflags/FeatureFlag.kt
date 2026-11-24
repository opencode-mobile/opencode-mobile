package dev.opencode.mobile.domain.featureflags

/**
 * Registry of remote feature flags (OPE-269). One shared snake_case key
 * namespace with iOS; keys and bundled defaults must match on both platforms.
 * Feature code reads flags through [FeatureFlags], never Firebase.
 */
enum class FeatureFlag(
    val key: String,
    val title: String,
    val description: String,
    val defaultValue: Boolean,
) {
    SessionComposerDocked(
        key = "session_composer_docked",
        title = "Docked composer",
        description = "Session Composer becomes a full-width bar attached to the bottom edge",
        defaultValue = false,
    ),
    ;

    companion object {
        /** Keys of removed flags, so Clear All Data still drops their stored overrides. */
        val RETIRED_KEYS = listOf("session_chat_chip_timeline")

        fun fromKey(key: String): FeatureFlag? = entries.firstOrNull { it.key == key }
    }
}
