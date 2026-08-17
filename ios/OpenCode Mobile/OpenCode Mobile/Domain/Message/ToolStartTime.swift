import Foundation

/// Wall-clock start for a running tool, used by the Child Session Row elapsed tick.
nonisolated enum ToolStartTime {
    /// Accepts seconds, milliseconds, or microseconds. Drops relative/seq values.
    static func normalizeEpochMs(_ raw: Int64?) -> Int64? {
        guard let raw, raw > 0 else { return nil }
        if raw > 100_000_000_000_000 { return raw / 1_000 }
        if raw > 100_000_000_000 { return raw }
        if raw > 1_000_000_000 { return raw * 1_000 }
        return nil
    }

    static func resolve(
        incoming: Int64?,
        existing: Int64?,
        status: ToolStatus,
        nowEpochMs: Int64
    ) -> Int64? {
        if let existing = usableEpochMs(existing, nowEpochMs: nowEpochMs) { return existing }
        if let incoming = usableEpochMs(incoming, nowEpochMs: nowEpochMs) { return incoming }
        if status == .pending || status == .running { return nowEpochMs }
        return nil
    }

    /// Drops a start that is more than two seconds in the future (device clock behind).
    private static func usableEpochMs(_ raw: Int64?, nowEpochMs: Int64) -> Int64? {
        guard let normalized = normalizeEpochMs(raw) else { return nil }
        guard normalized <= nowEpochMs + 2_000 else { return nil }
        return normalized
    }

    static func nowEpochMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
