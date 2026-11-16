package dev.opencode.mobile.domain.message

/**
 * When a closed session's in-memory transcript is released. A session stays loaded while
 * anything observes it (its Session Chat anywhere on the back stack, a tool-group screen, a
 * parent's activity card). Once the last observer stops, the most recently closed sessions stay
 * warm for a grace period and the rest are released; memory pressure releases every closed
 * session at once. Same names and values on iOS.
 */
object TranscriptRetentionPolicy {
    /** How long a closed session's transcript stays in memory after its last observer stops. */
    const val CLOSED_SESSION_GRACE_MS = 120_000L

    /** How many closed sessions keep their transcript during the grace period. */
    const val MAX_WARM_CLOSED_SESSIONS = 3
}
