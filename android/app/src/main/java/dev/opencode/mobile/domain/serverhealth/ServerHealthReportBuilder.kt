package dev.opencode.mobile.domain.serverhealth

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Turns recorded pings and events into the numbers Server Health shows. Pure; no clock of its own. */
object ServerHealthReportBuilder {
    const val RECENT_WINDOW_MS: Long = 30L * 60_000L
    const val ERROR_LOG_LIMIT: Int = 100

    /** Failed checks further apart than three ping intervals are separate incidents. */
    private const val ERROR_RUN_GAP_MS: Long = 90_000L

    private val dropTargets =
        setOf(
            ServerHealthConnectionState.Connecting,
            ServerHealthConnectionState.Reconnecting,
            ServerHealthConnectionState.Offline,
            ServerHealthConnectionState.AuthFailed,
        )
    private val troubleStates =
        setOf(
            ServerHealthConnectionState.Reconnecting,
            ServerHealthConnectionState.Offline,
            ServerHealthConnectionState.AuthFailed,
        )

    private data class Segment(
        val startMs: Long,
        val endMs: Long,
        val state: ServerHealthConnectionState,
    )

    private data class Transition(
        val atMs: Long,
        val from: ServerHealthConnectionState,
        val to: ServerHealthConnectionState,
    ) {
        val isDrop: Boolean
            get() = from == ServerHealthConnectionState.Live && to in dropTargets
    }

    /** Latencies of the checks in the last [windowMs], oldest first; a failed check is null. */
    fun recentPings(
        pings: List<ServerHealthPing>,
        nowMs: Long,
        windowMs: Long = RECENT_WINDOW_MS,
    ): List<Int?> =
        pings
            .filter { it.atMs in (nowMs - windowMs)..nowMs }
            .sortedBy { it.atMs }
            .map { if (it.failure == null) it.latencyMs else null }

    fun build(
        pings: List<ServerHealthPing>,
        events: List<ServerHealthEvent>,
        nowMs: Long,
        range: ServerHealthRange,
    ): ServerHealthReport {
        val rangeStart = nowMs - range.durationMs
        val stateEvents =
            events
                .filter { it.state != null }
                .sortedBy { it.atMs }
        val initial =
            stateEvents.lastOrNull { it.atMs < rangeStart }?.state ?: ServerHealthConnectionState.Stopped
        val segments = mutableListOf<Segment>()
        val transitions = mutableListOf<Transition>()
        var current = initial
        var cursor = rangeStart
        for (event in stateEvents) {
            if (event.atMs < rangeStart || event.atMs > nowMs) continue
            val next = event.state ?: continue
            if (event.atMs > cursor) segments += Segment(cursor, event.atMs, current)
            if (next != current) transitions += Transition(event.atMs, current, next)
            current = next
            cursor = max(cursor, event.atMs)
        }
        if (nowMs > cursor) segments += Segment(cursor, nowMs, current)

        val observedMs = segments.filter { it.state != ServerHealthConnectionState.Stopped }.sumOf { it.endMs - it.startMs }
        val liveMs = segments.filter { it.state == ServerHealthConnectionState.Live }.sumOf { it.endMs - it.startMs }
        val drops = transitions.filter { it.isDrop }
        val recoveries = recoveryTimes(transitions)

        val rangePings = pings.filter { it.atMs in rangeStart..nowMs }
        val failedPings = rangePings.filter { it.failure != null }
        val latencies = rangePings.mapNotNull { if (it.failure == null) it.latencyMs else null }

        val failureCounts = mutableMapOf<ServerHealthFailureCategory, Int>()
        events
            .filter { it.atMs in rangeStart..nowMs }
            .mapNotNull { it.failure }
            .forEach { failureCounts[it] = (failureCounts[it] ?: 0) + 1 }
        failedPings.mapNotNull { it.failure }.forEach { failureCounts[it] = (failureCounts[it] ?: 0) + 1 }
        val failuresByCategory =
            failureCounts
                .filterValues { it > 0 }
                .map { (category, count) -> ServerHealthFailureCount(category, count) }
                .sortedWith(compareByDescending<ServerHealthFailureCount> { it.count }.thenBy { it.category.ordinal })

        val restartEvents = events.filter { it.kind == ServerHealthEventKind.Restart }
        val restarts =
            restartEvents
                .filter { it.atMs in rangeStart..nowMs }
                .sortedBy { it.atMs }
                .map { ServerHealthRestart(it.atMs, it.value.ifEmpty { null }, it.detail) }

        val buckets = buckets(range, rangeStart, nowMs, segments, drops, rangePings)
        val errorLog = errorLog(rangePings, events.filter { it.atMs in rangeStart..nowMs })

        return ServerHealthReport(
            range = range,
            rangeStartMs = rangeStart,
            nowMs = nowMs,
            observedMs = observedMs,
            liveMs = liveMs,
            uptimePercent = if (observedMs > 0) liveMs.toDouble() / observedMs * 100.0 else null,
            drops = drops.size,
            medianRecoveryMs = median(recoveries),
            meanTimeBetweenDropsMs = if (drops.isNotEmpty()) liveMs / drops.size else null,
            checks = rangePings.size,
            failedChecks = failedPings.size,
            errorRatePercent = if (rangePings.isNotEmpty()) failedPings.size.toDouble() / rangePings.size * 100.0 else null,
            p50LatencyMs = percentile(latencies, 0.5),
            p95LatencyMs = percentile(latencies, 0.95),
            failuresByCategory = failuresByCategory,
            restarts = restarts,
            lastRestartAtMs = restartEvents.maxOfOrNull { it.atMs },
            buckets = buckets,
            openBuckets = openTimeline(buckets),
            errorLog = errorLog.take(ERROR_LOG_LIMIT),
            errorLogTotal = errorLog.size,
            isEmpty = pings.isEmpty() && events.isEmpty(),
        )
    }

    /** Drops closed-app time at both ends and shrinks each gap between app sessions to one bucket. */
    fun openTimeline(buckets: List<ServerHealthBucket>): List<ServerHealthBucket> {
        fun closed(bucket: ServerHealthBucket) = bucket.condition == ServerHealthBucketCondition.Unobserved
        val first = buckets.indexOfFirst { !closed(it) }
        if (first < 0) return emptyList()
        val last = buckets.indexOfLast { !closed(it) }
        val open = mutableListOf<ServerHealthBucket>()
        for (index in first..last) {
            val bucket = buckets[index]
            if (!closed(bucket) || !closed(open.last())) open += bucket
        }
        return open
    }

    /** Failed checks (runs merged) and stream failures, newest first. */
    private fun errorLog(
        pings: List<ServerHealthPing>,
        events: List<ServerHealthEvent>,
    ): List<ServerHealthErrorLogEntry> {
        val entries = mutableListOf<ServerHealthErrorLogEntry>()
        var run: ServerHealthErrorLogEntry? = null
        for (ping in pings.sortedBy { it.atMs }) {
            val category = ping.failure
            val current = run
            if (category == null) {
                current?.let { entries += it }
                run = null
            } else if (current != null && current.category == category && ping.atMs - current.lastAtMs <= ERROR_RUN_GAP_MS) {
                run = current.copy(lastAtMs = ping.atMs, count = current.count + 1)
            } else {
                current?.let { entries += it }
                run = ServerHealthErrorLogEntry(ping.atMs, ping.atMs, category, ServerHealthErrorSource.Check, 1)
            }
        }
        run?.let { entries += it }
        events.forEach { event ->
            val category = event.failure ?: return@forEach
            entries += ServerHealthErrorLogEntry(event.atMs, event.atMs, category, ServerHealthErrorSource.Stream, 1)
        }
        return entries.sortedWith(
            compareByDescending<ServerHealthErrorLogEntry> { it.lastAtMs }.thenBy { it.source.ordinal },
        )
    }

    /** Nearest-rank percentile: sorted ascending, index `ceil(p * n) - 1`. */
    fun percentile(
        values: List<Int>,
        p: Double,
    ): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** Middle value; the mean of the two middles when there is an even count. */
    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private fun recoveryTimes(transitions: List<Transition>): List<Long> {
        val recoveries = mutableListOf<Long>()
        transitions.forEachIndexed { index, transition ->
            if (!transition.isDrop) return@forEachIndexed
            for (later in transitions.subList(index + 1, transitions.size)) {
                if (later.to == ServerHealthConnectionState.Stopped) break
                if (later.to == ServerHealthConnectionState.Live) {
                    recoveries += later.atMs - transition.atMs
                    break
                }
            }
        }
        return recoveries
    }

    private fun buckets(
        range: ServerHealthRange,
        rangeStart: Long,
        nowMs: Long,
        segments: List<Segment>,
        drops: List<Transition>,
        pings: List<ServerHealthPing>,
    ): List<ServerHealthBucket> {
        val width = range.durationMs / range.bucketCount
        return (0 until range.bucketCount).map { index ->
            val start = rangeStart + index * width
            val last = index == range.bucketCount - 1
            val end = if (last) nowMs else start + width

            fun inBucket(atMs: Long): Boolean = atMs >= start && (atMs < end || (last && atMs <= end))
            var observed = 0L
            var trouble = 0L
            for (segment in segments) {
                val overlap = min(segment.endMs, end) - max(segment.startMs, start)
                if (overlap <= 0) continue
                if (segment.state != ServerHealthConnectionState.Stopped) observed += overlap
                if (segment.state in troubleStates) trouble += overlap
            }
            val bucketPings = pings.filter { inBucket(it.atMs) }
            val latencies = bucketPings.mapNotNull { if (it.failure == null) it.latencyMs else null }
            val bucketDrops = drops.count { inBucket(it.atMs) }
            val condition =
                when {
                    observed == 0L -> ServerHealthBucketCondition.Unobserved
                    trouble.toDouble() / observed > 0.5 -> ServerHealthBucketCondition.Down
                    trouble > 0 || bucketDrops > 0 -> ServerHealthBucketCondition.Degraded
                    else -> ServerHealthBucketCondition.Live
                }
            ServerHealthBucket(
                startMs = start,
                endMs = end,
                medianMs = percentile(latencies, 0.5),
                p95Ms = percentile(latencies, 0.95),
                drops = bucketDrops,
                checks = bucketPings.size,
                failedChecks = bucketPings.count { it.failure != null },
                condition = condition,
            )
        }
    }
}
