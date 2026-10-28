import Foundation

actor InMemorySessionCacheStore: SessionCacheStore {
    private struct Row {
        var key: SessionCacheKey
        var value: SessionCacheValue?
        var syncedAtEpochMs: Int64
        var retainedAtEpochMs: Int64?
    }

    private var rows: [String: Row] = [:]

    func load(key: SessionCacheKey) async -> SessionCacheRecord? {
        guard let row = rows[key.cacheKey], let value = row.value, !value.payloadJSON.isEmpty else {
            return nil
        }
        return SessionCacheRecord(
            value: value,
            syncedAtEpochMs: row.syncedAtEpochMs,
            retainedAtEpochMs: row.retainedAtEpochMs
        )
    }

    func commit(key: SessionCacheKey, value: SessionCacheValue, meta: SessionCacheCommitMeta) async {
        let existing = rows[key.cacheKey]
        rows[key.cacheKey] = Row(
            key: key,
            value: value,
            syncedAtEpochMs: meta.syncedAtEpochMs,
            retainedAtEpochMs: existing?.retainedAtEpochMs
        )
    }

    func retain(key: SessionCacheKey, reason: SessionCacheRetainReason, atEpochMs: Int64) async {
        if var existing = rows[key.cacheKey] {
            if existing.retainedAtEpochMs == nil || existing.retainedAtEpochMs == 0 {
                existing.retainedAtEpochMs = atEpochMs
                rows[key.cacheKey] = existing
            }
        } else {
            rows[key.cacheKey] = Row(
                key: key,
                value: SessionCacheValue(payloadJSON: "", schemaVersion: SessionCacheCodec.schemaVersion),
                syncedAtEpochMs: 0,
                retainedAtEpochMs: atEpochMs
            )
        }
    }

    func evict(_ selector: SessionCacheEvictSelector) async {
        switch selector {
        case .all:
            rows.removeAll()
        case let .worktree(prefix, worktree, matchByUrlPrefix):
            rows = rows.filter { _, row in
                !matchesWorktree(row.key, prefix: prefix, worktree: worktree, matchByUrlPrefix: matchByUrlPrefix)
            }
        case let .connection(prefix, matchByUrlPrefix):
            rows = rows.filter { _, row in
                !matchesConnection(row.key, prefix: prefix, matchByUrlPrefix: matchByUrlPrefix)
            }
        }
    }

    func report(_ selector: SessionCacheEvictSelector) async -> SessionCacheReport {
        let selected: [Row]
        switch selector {
        case .all:
            selected = Array(rows.values)
        case let .worktree(prefix, worktree, matchByUrlPrefix):
            selected = rows.values.filter {
                matchesWorktree($0.key, prefix: prefix, worktree: worktree, matchByUrlPrefix: matchByUrlPrefix)
            }
        case let .connection(prefix, matchByUrlPrefix):
            selected = rows.values.filter { matchesConnection($0.key, prefix: prefix, matchByUrlPrefix: matchByUrlPrefix) }
        }
        let bytes = selected.reduce(Int64(0)) { $0 + Int64($1.value?.payloadJSON.utf8.count ?? 0) }
        return SessionCacheReport(totalBytes: bytes, entryCount: Int64(selected.count))
    }

    private func matchesConnection(_ key: SessionCacheKey, prefix: String, matchByUrlPrefix: Bool) -> Bool {
        if matchByUrlPrefix {
            return key.connectionId.lowercased().hasPrefix(prefix.lowercased())
        }
        return key.connectionId.caseInsensitiveCompare(prefix) == .orderedSame
    }

    private func matchesWorktree(
        _ key: SessionCacheKey,
        prefix: String,
        worktree: String,
        matchByUrlPrefix: Bool
    ) -> Bool {
        guard key.worktree == worktree else { return false }
        if matchByUrlPrefix {
            return key.connectionId.lowercased().hasPrefix(prefix.lowercased())
        }
        return key.connectionId.caseInsensitiveCompare(prefix) == .orderedSame
    }
}
