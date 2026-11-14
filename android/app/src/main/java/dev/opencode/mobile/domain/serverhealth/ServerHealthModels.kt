package dev.opencode.mobile.domain.serverhealth

import dev.opencode.mobile.domain.event.EventStreamConnectionState

/** How the app's live connection to a server looks, as recorded for Server Health. [raw] is persisted. */
enum class ServerHealthConnectionState(
    val raw: String,
) {
    Live("live"),
    Connecting("connecting"),
    Reconnecting("reconnecting"),
    Offline("offline"),
    AuthFailed("authFailed"),

    /** Not measured: the app is in the background, the stream is stopped, or no profile is active. */
    Stopped("stopped"),
    ;

    companion object {
        fun fromRaw(raw: String): ServerHealthConnectionState? = entries.firstOrNull { it.raw == raw }

        fun from(state: EventStreamConnectionState): ServerHealthConnectionState =
            when (state) {
                EventStreamConnectionState.Idle -> Stopped
                EventStreamConnectionState.Connecting -> Connecting
                EventStreamConnectionState.Connected -> Live
                EventStreamConnectionState.Disconnected -> Offline
                EventStreamConnectionState.Reconnecting -> Reconnecting
                EventStreamConnectionState.AuthFailed -> AuthFailed
            }
    }
}

/** Why a check or a stream failed. [raw] is persisted. */
enum class ServerHealthFailureCategory(
    val raw: String,
) {
    Network("network"),
    Timeout("timeout"),
    Heartbeat("heartbeat"),
    Server("server"),
    Auth("auth"),
    Other("other"),
    ;

    companion object {
        fun fromRaw(raw: String): ServerHealthFailureCategory? = entries.firstOrNull { it.raw == raw }
    }
}

/** One health check. Exactly one of [latencyMs] and [failure] is set. */
data class ServerHealthPing(
    val profileId: String,
    val atMs: Long,
    val latencyMs: Int?,
    val failure: ServerHealthFailureCategory?,
    val pid: Int? = null,
    val version: String? = null,
) {
    val succeeded: Boolean
        get() = failure == null && latencyMs != null
}

enum class ServerHealthEventKind(
    val raw: String,
) {
    State("state"),
    Failure("failure"),
    Restart("restart"),
    ;

    companion object {
        fun fromRaw(raw: String): ServerHealthEventKind? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * A recorded change. State: [value] is a [ServerHealthConnectionState.raw]. Failure: a
 * [ServerHealthFailureCategory.raw] for a stream drop. Restart: the new version or "", with the
 * previous version in [detail].
 */
data class ServerHealthEvent(
    val profileId: String,
    val atMs: Long,
    val kind: ServerHealthEventKind,
    val value: String,
    val detail: String? = null,
) {
    val state: ServerHealthConnectionState?
        get() = if (kind == ServerHealthEventKind.State) ServerHealthConnectionState.fromRaw(value) else null

    val failure: ServerHealthFailureCategory?
        get() = if (kind == ServerHealthEventKind.Failure) ServerHealthFailureCategory.fromRaw(value) else null

    companion object {
        fun state(
            profileId: String,
            atMs: Long,
            state: ServerHealthConnectionState,
        ): ServerHealthEvent = ServerHealthEvent(profileId, atMs, ServerHealthEventKind.State, state.raw)

        fun failure(
            profileId: String,
            atMs: Long,
            category: ServerHealthFailureCategory,
        ): ServerHealthEvent = ServerHealthEvent(profileId, atMs, ServerHealthEventKind.Failure, category.raw)

        fun restart(
            profileId: String,
            atMs: Long,
            version: String?,
            previousVersion: String?,
        ): ServerHealthEvent = ServerHealthEvent(profileId, atMs, ServerHealthEventKind.Restart, version.orEmpty(), previousVersion)
    }
}

/** The active profile's connection right now. [lastPingMs] is null when the latest check failed. */
data class ServerHealthLiveStatus(
    val profileId: String? = null,
    val state: ServerHealthConnectionState = ServerHealthConnectionState.Stopped,
    val lastPingMs: Int? = null,
    val lastVersion: String? = null,
    val liveTargets: Int = 0,
    val requiredTargets: Int = 0,
)

enum class ServerHealthRange(
    val durationMs: Long,
    val bucketCount: Int,
) {
    Hour(60L * 60_000L, 60),
    Day(24L * 60L * 60_000L, 96),
    Week(7L * 24L * 60L * 60_000L, 84),
}

enum class ServerHealthBucketCondition {
    Live,
    Degraded,
    Down,
    Unobserved,
}

data class ServerHealthBucket(
    val startMs: Long,
    val endMs: Long,
    val medianMs: Int?,
    val p95Ms: Int?,
    val drops: Int,
    val checks: Int,
    val failedChecks: Int,
    val condition: ServerHealthBucketCondition,
)

data class ServerHealthFailureCount(
    val category: ServerHealthFailureCategory,
    val count: Int,
)

data class ServerHealthRestart(
    val atMs: Long,
    val version: String?,
    val previousVersion: String?,
)

data class ServerHealthReport(
    val range: ServerHealthRange,
    val rangeStartMs: Long,
    val nowMs: Long,
    val observedMs: Long,
    val liveMs: Long,
    val uptimePercent: Double?,
    val drops: Int,
    val medianRecoveryMs: Long?,
    val meanTimeBetweenDropsMs: Long?,
    val checks: Int,
    val failedChecks: Int,
    val errorRatePercent: Double?,
    val p50LatencyMs: Int?,
    val p95LatencyMs: Int?,
    val failuresByCategory: List<ServerHealthFailureCount>,
    val restarts: List<ServerHealthRestart>,
    val lastRestartAtMs: Long?,
    val buckets: List<ServerHealthBucket>,
    val isEmpty: Boolean,
)
