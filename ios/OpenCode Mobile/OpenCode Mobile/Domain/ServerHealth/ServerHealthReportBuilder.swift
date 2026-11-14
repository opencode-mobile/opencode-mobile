import Foundation

/// Builds Server Health reports from recorded checks and events. Pure: no clock, no store.
nonisolated enum ServerHealthReportBuilder {
    static let recentWindowMs: Int64 = 30 * 60_000

    static func build(
        pings: [ServerHealthPing],
        events: [ServerHealthEvent],
        nowMs: Int64,
        range: ServerHealthRange
    ) -> ServerHealthReport {
        let rangeStart = nowMs - range.durationMs
        let timeline = Timeline(events: events, rangeStart: rangeStart, nowMs: nowMs)

        let observedMs = timeline.time(from: rangeStart, to: nowMs) { $0 != .stopped }
        let liveMs = timeline.time(from: rangeStart, to: nowMs) { $0 == .live }
        let drops = timeline.dropTimes
        let recoveries = timeline.recoveryDurations

        let rangePings = pings
            .filter { $0.atMs >= rangeStart && $0.atMs <= nowMs }
            .sorted { $0.atMs < $1.atMs }
        let failedPings = rangePings.filter { $0.failure != nil }
        let latencies = rangePings.compactMap(\.latencyMs).sorted()

        var failureCounts: [ServerHealthFailureCategory: Int] = [:]
        for event in events where event.atMs >= rangeStart && event.atMs <= nowMs {
            if let category = event.failureCategory {
                failureCounts[category, default: 0] += 1
            }
        }
        for ping in failedPings {
            if let category = ping.failure {
                failureCounts[category, default: 0] += 1
            }
        }
        let order = ServerHealthFailureCategory.allCases
        let failuresByCategory = failureCounts
            .filter { $0.value > 0 }
            .map { ServerHealthFailureCount(category: $0.key, count: $0.value) }
            .sorted { lhs, rhs in
                if lhs.count != rhs.count { return lhs.count > rhs.count }
                return (order.firstIndex(of: lhs.category) ?? 0) < (order.firstIndex(of: rhs.category) ?? 0)
            }

        let restartEvents = events.filter { $0.kind == .restart }
        let restarts = restartEvents
            .filter { $0.atMs >= rangeStart && $0.atMs <= nowMs }
            .sorted { $0.atMs < $1.atMs }
            .map { ServerHealthRestart(atMs: $0.atMs, version: $0.value.isEmpty ? nil : $0.value, previousVersion: $0.detail) }

        let bucketCount = range.bucketCount
        let width = range.durationMs / Int64(bucketCount)
        var buckets: [ServerHealthBucket] = []
        buckets.reserveCapacity(bucketCount)
        for index in 0 ..< bucketCount {
            let start = rangeStart + Int64(index) * width
            let end = index == bucketCount - 1 ? nowMs : start + width
            let isLast = index == bucketCount - 1
            func contains(_ time: Int64) -> Bool {
                time >= start && (isLast ? time <= end : time < end)
            }
            let bucketPings = rangePings.filter { contains($0.atMs) }
            let bucketLatencies = bucketPings.compactMap(\.latencyMs).sorted()
            let bucketDrops = drops.filter { contains($0) }.count
            let observed = timeline.time(from: start, to: end) { $0 != .stopped }
            let trouble = timeline.time(from: start, to: end) { $0.isTrouble }
            let condition: ServerHealthBucketCondition
            if observed == 0 {
                condition = .unobserved
            } else if Double(trouble) / Double(observed) > 0.5 {
                condition = .down
            } else if trouble > 0 || bucketDrops > 0 {
                condition = .degraded
            } else {
                condition = .live
            }
            buckets.append(ServerHealthBucket(
                startMs: start,
                endMs: end,
                medianMs: percentile(bucketLatencies, 50),
                p95Ms: percentile(bucketLatencies, 95),
                drops: bucketDrops,
                checks: bucketPings.count,
                failedChecks: bucketPings.filter { $0.failure != nil }.count,
                condition: condition
            ))
        }

        return ServerHealthReport(
            range: range,
            rangeStartMs: rangeStart,
            nowMs: nowMs,
            observedMs: observedMs,
            liveMs: liveMs,
            uptimePercent: observedMs > 0 ? Double(liveMs) / Double(observedMs) * 100 : nil,
            drops: drops.count,
            medianRecoveryMs: median(recoveries),
            meanTimeBetweenDropsMs: drops.isEmpty ? nil : liveMs / Int64(drops.count),
            checks: rangePings.count,
            failedChecks: failedPings.count,
            errorRatePercent: rangePings.isEmpty ? nil : Double(failedPings.count) / Double(rangePings.count) * 100,
            p50LatencyMs: percentile(latencies, 50),
            p95LatencyMs: percentile(latencies, 95),
            failuresByCategory: failuresByCategory,
            restarts: restarts,
            lastRestartAtMs: restartEvents.map(\.atMs).max(),
            buckets: buckets,
            isEmpty: pings.isEmpty && events.isEmpty
        )
    }

    /// Latencies of the checks in the last `windowMs`, oldest first; a failed check is nil.
    static func recentPings(
        _ pings: [ServerHealthPing],
        nowMs: Int64,
        windowMs: Int64 = recentWindowMs
    ) -> [Int?] {
        pings
            .filter { $0.atMs >= nowMs - windowMs && $0.atMs <= nowMs }
            .sorted { $0.atMs < $1.atMs }
            .map(\.latencyMs)
    }

    /// Nearest rank over ascending `sorted`: index = ceil(p/100 * n) - 1. Nil when empty.
    static func percentile(_ sorted: [Int], _ percent: Int) -> Int? {
        guard !sorted.isEmpty else { return nil }
        let rank = (percent * sorted.count + 99) / 100
        let index = min(max(rank - 1, 0), sorted.count - 1)
        return sorted[index]
    }

    /// Middle value; the average of the two middle values when the count is even. Nil when empty.
    static func median(_ values: [Int64]) -> Int64? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        let middle = sorted.count / 2
        if sorted.count.isMultiple(of: 2) {
            return (sorted[middle - 1] + sorted[middle]) / 2
        }
        return sorted[middle]
    }
}

/// State segments clipped to the range, plus the transitions inside it.
private nonisolated struct Timeline {
    struct Segment {
        let start: Int64
        let end: Int64
        let state: ServerHealthConnectionState
    }

    struct Transition {
        let atMs: Int64
        let from: ServerHealthConnectionState
        let to: ServerHealthConnectionState
    }

    private(set) var segments: [Segment] = []
    private(set) var transitions: [Transition] = []

    init(events: [ServerHealthEvent], rangeStart: Int64, nowMs: Int64) {
        struct Entry {
            let offset: Int
            let atMs: Int64
            let state: ServerHealthConnectionState
        }
        var entries: [Entry] = []
        for (offset, event) in events.enumerated() {
            if let state = event.connectionState {
                entries.append(Entry(offset: offset, atMs: event.atMs, state: state))
            }
        }
        let states = entries.sorted { lhs, rhs in
            lhs.atMs == rhs.atMs ? lhs.offset < rhs.offset : lhs.atMs < rhs.atMs
        }
        var current: ServerHealthConnectionState = states.last { $0.atMs < rangeStart }?.state ?? .stopped
        var cursor = rangeStart
        for entry in states where entry.atMs >= rangeStart && entry.atMs <= nowMs {
            let atMs = entry.atMs
            let state = entry.state
            if atMs > cursor {
                segments.append(Segment(start: cursor, end: atMs, state: current))
                cursor = atMs
            }
            if state != current {
                transitions.append(Transition(atMs: atMs, from: current, to: state))
                current = state
            }
        }
        if nowMs > cursor {
            segments.append(Segment(start: cursor, end: nowMs, state: current))
        }
    }

    /// Times of transitions from live into connecting, reconnecting, offline, or auth failed.
    var dropTimes: [Int64] {
        transitions.filter(Self.isDrop).map(\.atMs)
    }

    /// For each drop, the time until the next live, unless stopped comes first or live never does.
    var recoveryDurations: [Int64] {
        var durations: [Int64] = []
        for (index, transition) in transitions.enumerated() where Self.isDrop(transition) {
            for later in transitions[(index + 1)...] {
                if later.to == .stopped { break }
                if later.to == .live {
                    durations.append(later.atMs - transition.atMs)
                    break
                }
            }
        }
        return durations
    }

    func time(from start: Int64, to end: Int64, where matches: (ServerHealthConnectionState) -> Bool) -> Int64 {
        guard end > start else { return 0 }
        var total: Int64 = 0
        for segment in segments where matches(segment.state) {
            let overlap = min(segment.end, end) - max(segment.start, start)
            if overlap > 0 { total += overlap }
        }
        return total
    }

    private static func isDrop(_ transition: Transition) -> Bool {
        transition.from == .live && transition.to != .live && transition.to != .stopped
    }
}
