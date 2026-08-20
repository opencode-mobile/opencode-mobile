package dev.opencode.mobile.domain.event

/**
 * App-level return-to-foreground restore. Starts SSE and REST-authorizes
 * registered UI scopes. Cold resume after background stop is not gated on
 * stream.reconnected delivery alone.
 */
interface ForegroundRestore {
    /** Foreground edge after the background stop grace (or home enter). */
    fun restore()

    /** App left foreground: arm REST catch-up on next restore. Does not stop SSE. */
    fun noteBackground()

    /** Background grace fired: tear down SSE and arm cold-resume catch-up. */
    fun noteBackgroundStop()

    fun setOpenSession(
        sessionId: String?,
        directory: String?,
    )

    fun setHomeVisible(visible: Boolean)
}

enum class ForegroundRestorePhase {
    Idle,
    Restoring,
    Live,
    Failed,
}
