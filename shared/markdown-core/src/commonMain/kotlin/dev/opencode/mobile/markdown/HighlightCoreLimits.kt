package dev.opencode.mobile.markdown

/**
 * Chat code-fence highlighting limits shared by both apps: the Kotlin [HighlightCoreHighlightingEngine]
 * on Android and the Swift engine on iOS read these, so the platforms cannot drift.
 */
object HighlightCoreLimits {
    /** Per-request deadline; a fence that misses it comes back Partial and continues on the next request. */
    const val CHAT_DEADLINE_MILLIS: Double = 50.0

    /** Most fence sessions kept at once; the least recently used go first. */
    const val CHAT_MAX_SESSIONS: Int = 64

    /** Most source characters kept across all fence sessions. */
    const val CHAT_MAX_CHARACTERS: Int = 1_000_000

    /** Moderate memory pressure keeps this share of sessions and of characters; Critical keeps none. */
    const val MODERATE_PRESSURE_KEEP_DIVISOR: Int = 2
}
