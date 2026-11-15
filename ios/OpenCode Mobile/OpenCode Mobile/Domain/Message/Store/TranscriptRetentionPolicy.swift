import Foundation

/// When a closed session's in-memory transcript is released. A session stays loaded while
/// anything observes it (its Session Chat anywhere in the navigation stack, a tool-group
/// screen, a parent's activity card). Once the last observer stops, the most recently closed
/// sessions stay warm for a grace period and the rest are released; a memory warning releases
/// every closed session at once. Same names and values on Android.
nonisolated enum TranscriptRetentionPolicy {
    /// How long a closed session's transcript stays in memory after its last observer stops.
    static let closedSessionGraceMs = 120_000

    /// How many closed sessions keep their transcript during the grace period.
    static let maxWarmClosedSessions = 3
}
