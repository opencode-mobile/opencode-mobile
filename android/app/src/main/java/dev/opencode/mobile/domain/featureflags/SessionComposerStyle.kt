package dev.opencode.mobile.domain.featureflags

/** How the Session Composer separates from the timeline. Shared with iOS. */
enum class SessionComposerStyle {
    Floating,
    OutlineFade,
    Docked,
    ;

    companion object {
        /** Docked wins when both flags are on. */
        fun resolve(
            outlineFade: Boolean,
            docked: Boolean,
        ): SessionComposerStyle =
            when {
                docked -> Docked
                outlineFade -> OutlineFade
                else -> Floating
            }
    }
}
