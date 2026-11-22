package dev.opencode.mobile.domain.featureflags

/** How the Session Composer separates from the timeline. Shared with iOS. */
enum class SessionComposerStyle {
    OutlineFade,
    Docked,
    ;

    companion object {
        fun resolve(docked: Boolean): SessionComposerStyle = if (docked) Docked else OutlineFade
    }
}
