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
    ;

    companion object {
        fun fromKey(key: String): FeatureFlag? = entries.firstOrNull { it.key == key }
    }
}
