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
    SessionChatChipTimeline(
        key = "session_chat_chip_timeline",
        title = "Chip timeline",
        description = "Session Chat chip timeline mode and its Settings toggle",
        defaultValue = true,
    ),
    SessionComposerOutlineFade(
        key = "session_composer_outline_fade",
        title = "Composer outline and fade",
        description = "Session Composer gets an outline and the timeline fades out above it",
        defaultValue = false,
    ),
    SessionComposerDocked(
        key = "session_composer_docked",
        title = "Docked composer",
        description = "Session Composer becomes a full-width bar attached to the bottom edge",
        defaultValue = false,
    ),
    ;

    companion object {
        fun fromKey(key: String): FeatureFlag? = entries.firstOrNull { it.key == key }
    }
}
