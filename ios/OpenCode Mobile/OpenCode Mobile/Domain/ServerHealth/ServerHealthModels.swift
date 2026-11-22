import Foundation

/// How the live connection to a server stood at a moment, as Server Health records it.
/// Raw values are the persisted strings and match Android.
nonisolated enum ServerHealthConnectionState: String, CaseIterable, Equatable, Sendable {
    case live
    case connecting
    case reconnecting
    case offline
    case authFailed
    /// Not measured: the app is in the background, the stream is stopped, or no profile is active.
    case stopped

    init(_ state: EventStreamConnectionState) {
        switch state {
        case .idle: self = .stopped
        case .connecting: self = .connecting
        case .connected: self = .live
        case .disconnected: self = .offline
        case .reconnecting: self = .reconnecting
        case .authFailed: self = .authFailed
        }
    }

    /// Time in these states counts as trouble in a bucket. `connecting` is neutral.
    var isTrouble: Bool {
        self == .reconnecting || self == .offline || self == .authFailed
    }
}

/// Why a check or a stream attempt failed. Raw values are the persisted strings and match Android.
nonisolated enum ServerHealthFailureCategory: String, CaseIterable, Equatable, Sendable {
    /// Transport errors that are not timeouts.
    case network
    /// Connect or request timeouts, and the SSE first-frame (handshake) timeout.
    case timeout
    /// The idle watchdog fired: no heartbeat or data for 45 s.
    case heartbeat
    /// HTTP 5xx, health said `healthy: false`, or the server ended the stream.
    case server
    /// HTTP 401 or 403.
    case auth
    /// Any other HTTP status, or a body that does not decode.
    case other
}

/// One health check against the server. Exactly one of `latencyMs` and `failure` is set.
nonisolated struct ServerHealthPing: Equatable, Sendable {
    let profileID: String
    let atMs: Int64
    let latencyMs: Int?
    let failure: ServerHealthFailureCategory?
    let pid: Int?
    let version: String?

    static func success(profileID: String, atMs: Int64, latencyMs: Int, pid: Int? = nil, version: String? = nil) -> ServerHealthPing {
        ServerHealthPing(profileID: profileID, atMs: atMs, latencyMs: latencyMs, failure: nil, pid: pid, version: version)
    }

    static func failed(profileID: String, atMs: Int64, failure: ServerHealthFailureCategory) -> ServerHealthPing {
        ServerHealthPing(profileID: profileID, atMs: atMs, latencyMs: nil, failure: failure, pid: nil, version: nil)
    }
}

nonisolated enum ServerHealthEventKind: String, CaseIterable, Equatable, Sendable {
    /// `value` is a `ServerHealthConnectionState` raw value.
    case state
    /// `value` is a `ServerHealthFailureCategory` raw value; stream failures only.
    case failure
    /// `value` is the new version or ""; `detail` is the previous version.
    case restart
}

nonisolated struct ServerHealthEvent: Equatable, Sendable {
    let profileID: String
    let atMs: Int64
    let kind: ServerHealthEventKind
    let value: String
    let detail: String?

    init(profileID: String, atMs: Int64, kind: ServerHealthEventKind, value: String, detail: String? = nil) {
        self.profileID = profileID
        self.atMs = atMs
        self.kind = kind
        self.value = value
        self.detail = detail
    }

    static func state(_ state: ServerHealthConnectionState, profileID: String, atMs: Int64) -> ServerHealthEvent {
        ServerHealthEvent(profileID: profileID, atMs: atMs, kind: .state, value: state.rawValue)
    }

    static func failure(_ category: ServerHealthFailureCategory, profileID: String, atMs: Int64) -> ServerHealthEvent {
        ServerHealthEvent(profileID: profileID, atMs: atMs, kind: .failure, value: category.rawValue)
    }

    static func restart(version: String?, previousVersion: String?, profileID: String, atMs: Int64) -> ServerHealthEvent {
        ServerHealthEvent(profileID: profileID, atMs: atMs, kind: .restart, value: version ?? "", detail: previousVersion)
    }

    var connectionState: ServerHealthConnectionState? {
        kind == .state ? ServerHealthConnectionState(rawValue: value) : nil
    }

    var failureCategory: ServerHealthFailureCategory? {
        kind == .failure ? ServerHealthFailureCategory(rawValue: value) : nil
    }
}

/// The server process a successful check saw, for restart detection.
nonisolated struct ServerHealthProcessIdentity: Equatable, Sendable {
    let pid: Int?
    let version: String?
}

/// What the monitor knows right now about the active profile.
nonisolated struct ServerHealthLiveStatus: Equatable, Sendable {
    var profileID: String?
    var state: ServerHealthConnectionState
    /// Latency of the most recent check if it succeeded, else nil.
    var lastPingMs: Int?
    var lastVersion: String?
    var liveTargets: Int
    var requiredTargets: Int

    static let stopped = ServerHealthLiveStatus(
        profileID: nil,
        state: .stopped,
        lastPingMs: nil,
        lastVersion: nil,
        liveTargets: 0,
        requiredTargets: 0
    )
}

nonisolated enum ServerHealthRange: String, CaseIterable, Equatable, Hashable, Sendable {
    case hour
    case day
    case week

    var durationMs: Int64 {
        switch self {
        case .hour: 60 * 60_000
        case .day: 24 * 60 * 60_000
        case .week: 7 * 24 * 60 * 60_000
        }
    }

    var bucketCount: Int {
        switch self {
        case .hour: 60
        case .day: 96
        case .week: 84
        }
    }
}

nonisolated enum ServerHealthBucketCondition: String, Equatable, Sendable {
    case live
    case degraded
    case down
    case unobserved
}

nonisolated struct ServerHealthBucket: Equatable, Sendable {
    let startMs: Int64
    let endMs: Int64
    let medianMs: Int?
    let p95Ms: Int?
    let drops: Int
    let checks: Int
    let failedChecks: Int
    let condition: ServerHealthBucketCondition
}

nonisolated struct ServerHealthFailureCount: Equatable, Sendable {
    let category: ServerHealthFailureCategory
    let count: Int
}

nonisolated struct ServerHealthRestart: Equatable, Sendable {
    let atMs: Int64
    let version: String?
    let previousVersion: String?
}

/// Where a logged failure was seen.
nonisolated enum ServerHealthErrorSource: String, Equatable, Sendable {
    case check
    case stream
}

/// One line of the error log. Consecutive failed checks of one category share a line, with their `count`.
nonisolated struct ServerHealthErrorLogEntry: Equatable, Sendable {
    let firstAtMs: Int64
    let lastAtMs: Int64
    let category: ServerHealthFailureCategory
    let source: ServerHealthErrorSource
    let count: Int
}

nonisolated struct ServerHealthReport: Equatable, Sendable {
    let range: ServerHealthRange
    let rangeStartMs: Int64
    let nowMs: Int64
    let observedMs: Int64
    let liveMs: Int64
    let uptimePercent: Double?
    let drops: Int
    let medianRecoveryMs: Int64?
    let meanTimeBetweenDropsMs: Int64?
    let checks: Int
    let failedChecks: Int
    let errorRatePercent: Double?
    let p50LatencyMs: Int?
    let p95LatencyMs: Int?
    let failuresByCategory: [ServerHealthFailureCount]
    let restarts: [ServerHealthRestart]
    let lastRestartAtMs: Int64?
    let buckets: [ServerHealthBucket]
    /// `buckets` without closed-app time: none at the ends, and one unobserved bucket per gap between.
    let openBuckets: [ServerHealthBucket]
    /// Newest first, at most `ServerHealthReportBuilder.errorLogLimit` lines.
    let errorLog: [ServerHealthErrorLogEntry]
    /// Lines in the range before the limit.
    let errorLogTotal: Int
    /// No checks and no events in the whole loaded window.
    let isEmpty: Bool
}

/// The Servers page summary under the active profile's card.
nonisolated struct ServerHealthQuickView: Equatable, Sendable {
    let state: ServerHealthConnectionState
    let currentPingMs: Int?
    let recentPings: [Int?]
    /// Over the last 24 hours.
    let uptimePercent: Double?
    /// Drops over the last 24 hours.
    let reconnects: Int
}

nonisolated enum ServerHealthRetention {
    static let windowMs: Int64 = 7 * 24 * 60 * 60_000
    static let pruneIntervalMs: Int64 = 60 * 60_000
}
