import Foundation
import SwiftData

@Model
final class SessionCacheSnapshotRecord {
    @Attribute(.unique) var cacheKey: String
    var connectionId: String
    var worktree: String
    var kind: String
    var entryId: String
    var payloadJSON: String
    var schemaVersion: Int
    var syncedAtEpochMs: Int64
    var retainedAtEpochMs: Int64?

    init(
        cacheKey: String,
        connectionId: String,
        worktree: String,
        kind: String,
        entryId: String,
        payloadJSON: String,
        schemaVersion: Int,
        syncedAtEpochMs: Int64,
        retainedAtEpochMs: Int64?
    ) {
        self.cacheKey = cacheKey
        self.connectionId = connectionId
        self.worktree = worktree
        self.kind = kind
        self.entryId = entryId
        self.payloadJSON = payloadJSON
        self.schemaVersion = schemaVersion
        self.syncedAtEpochMs = syncedAtEpochMs
        self.retainedAtEpochMs = retainedAtEpochMs
    }
}

nonisolated final class SwiftDataSessionCacheStore: SessionCacheStore, @unchecked Sendable {
    private let container: ModelContainer

    init(container: ModelContainer) {
        self.container = container
    }

    static func makePersistentContainer() throws -> ModelContainer {
        let schema = Schema([SessionCacheSnapshotRecord.self])
        // Transcripts live in a backup-excluded, file-protected directory. The store used to sit
        // at SwiftData's default location; move it once so nothing is lost.
        let directory = try ProtectedStorage.directory("SessionCache")
        let url = directory.appendingPathComponent("session_cache.store", isDirectory: false)
        migrateLegacyStore(to: url)
        let configuration = ModelConfiguration("session_cache", schema: schema, url: url)
        let container = try ModelContainer(for: schema, configurations: [configuration])
        for suffix in ["", "-wal", "-shm"] {
            ProtectedStorage.protect(file: URL(fileURLWithPath: url.path + suffix))
        }
        return container
    }

    private static func migrateLegacyStore(to url: URL) {
        let fileManager = FileManager.default
        guard !fileManager.fileExists(atPath: url.path),
              let applicationSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        else { return }
        let legacy = applicationSupport.appendingPathComponent("session_cache.store", isDirectory: false)
        guard fileManager.fileExists(atPath: legacy.path) else { return }
        for suffix in ["", "-wal", "-shm"] {
            let from = URL(fileURLWithPath: legacy.path + suffix)
            let to = URL(fileURLWithPath: url.path + suffix)
            guard fileManager.fileExists(atPath: from.path) else { continue }
            try? fileManager.moveItem(at: from, to: to)
        }
    }

    static func makeInMemoryContainer() throws -> ModelContainer {
        let schema = Schema([SessionCacheSnapshotRecord.self])
        let configuration = ModelConfiguration(
            "session_cache_memory",
            schema: schema,
            isStoredInMemoryOnly: true
        )
        return try ModelContainer(for: schema, configurations: [configuration])
    }

    func load(key: SessionCacheKey) async -> SessionCacheRecord? {
        do {
            let context = ModelContext(container)
            guard let row = try fetch(cacheKey: key.cacheKey, in: context) else { return nil }
            guard !row.payloadJSON.isEmpty else { return nil }
            return SessionCacheRecord(
                value: SessionCacheValue(
                    payloadJSON: row.payloadJSON,
                    schemaVersion: row.schemaVersion
                ),
                syncedAtEpochMs: row.syncedAtEpochMs,
                retainedAtEpochMs: row.retainedAtEpochMs
            )
        } catch {
            DiagnosticLog.warning(.session, "SwiftData session cache load failed: \(error.localizedDescription)")
            return nil
        }
    }

    func commit(key: SessionCacheKey, value: SessionCacheValue, meta: SessionCacheCommitMeta) async {
        do {
            let context = ModelContext(container)
            let existing = try fetch(cacheKey: key.cacheKey, in: context)
            let retainedAt = existing?.retainedAtEpochMs
            if let existing {
                existing.connectionId = key.connectionId
                existing.worktree = key.worktree
                existing.kind = key.kind.rawValue
                existing.entryId = key.entryId
                existing.payloadJSON = value.payloadJSON
                existing.schemaVersion = value.schemaVersion
                existing.syncedAtEpochMs = meta.syncedAtEpochMs
                existing.retainedAtEpochMs = retainedAt
            } else {
                context.insert(
                    SessionCacheSnapshotRecord(
                        cacheKey: key.cacheKey,
                        connectionId: key.connectionId,
                        worktree: key.worktree,
                        kind: key.kind.rawValue,
                        entryId: key.entryId,
                        payloadJSON: value.payloadJSON,
                        schemaVersion: value.schemaVersion,
                        syncedAtEpochMs: meta.syncedAtEpochMs,
                        retainedAtEpochMs: retainedAt
                    )
                )
            }
            try context.save()
        } catch {
            DiagnosticLog.warning(.session, "SwiftData session cache commit failed: \(error.localizedDescription)")
        }
    }

    func retain(key: SessionCacheKey, reason: SessionCacheRetainReason, atEpochMs: Int64) async {
        do {
            let context = ModelContext(container)
            if let existing = try fetch(cacheKey: key.cacheKey, in: context) {
                if existing.retainedAtEpochMs == nil || existing.retainedAtEpochMs == 0 {
                    existing.retainedAtEpochMs = atEpochMs
                    try context.save()
                }
            } else {
                context.insert(
                    SessionCacheSnapshotRecord(
                        cacheKey: key.cacheKey,
                        connectionId: key.connectionId,
                        worktree: key.worktree,
                        kind: key.kind.rawValue,
                        entryId: key.entryId,
                        payloadJSON: "",
                        schemaVersion: SessionCacheCodec.schemaVersion,
                        syncedAtEpochMs: 0,
                        retainedAtEpochMs: atEpochMs
                    )
                )
                try context.save()
            }
        } catch {
            DiagnosticLog.warning(.session, "SwiftData session cache retain failed: \(error.localizedDescription)")
        }
    }

    func evict(_ selector: SessionCacheEvictSelector) async {
        do {
            let context = ModelContext(container)
            switch selector {
            case .all:
                try context.delete(model: SessionCacheSnapshotRecord.self)
            case let .worktree(connectionIdOrUrlPrefix, worktree, matchByUrlPrefix):
                let descriptor = FetchDescriptor<SessionCacheSnapshotRecord>(
                    predicate: #Predicate { $0.worktree == worktree }
                )
                let rows = try context.fetch(descriptor)
                for row in rows {
                    let matches: Bool
                    if matchByUrlPrefix {
                        matches = row.connectionId.lowercased()
                            .hasPrefix(connectionIdOrUrlPrefix.lowercased())
                    } else {
                        matches = row.connectionId.caseInsensitiveCompare(connectionIdOrUrlPrefix) == .orderedSame
                    }
                    if matches {
                        context.delete(row)
                    }
                }
            case let .connection(connectionIdOrUrlPrefix, matchByUrlPrefix):
                let rows = try context.fetch(FetchDescriptor<SessionCacheSnapshotRecord>())
                for row in rows where Self.matchesConnection(
                    row.connectionId,
                    connectionIdOrUrlPrefix: connectionIdOrUrlPrefix,
                    matchByUrlPrefix: matchByUrlPrefix
                ) {
                    context.delete(row)
                }
            }
            try context.save()
        } catch {
            DiagnosticLog.warning(.session, "SwiftData session cache evict failed: \(error.localizedDescription)")
        }
    }

    func report(_ selector: SessionCacheEvictSelector) async -> SessionCacheReport {
        do {
            let context = ModelContext(container)
            let rows: [SessionCacheSnapshotRecord]
            switch selector {
            case .all:
                rows = try context.fetch(FetchDescriptor<SessionCacheSnapshotRecord>())
            case let .worktree(connectionIdOrUrlPrefix, worktree, matchByUrlPrefix):
                let descriptor = FetchDescriptor<SessionCacheSnapshotRecord>(
                    predicate: #Predicate { $0.worktree == worktree }
                )
                rows = try context.fetch(descriptor).filter { row in
                    if matchByUrlPrefix {
                        row.connectionId.lowercased()
                            .hasPrefix(connectionIdOrUrlPrefix.lowercased())
                    } else {
                        row.connectionId.caseInsensitiveCompare(connectionIdOrUrlPrefix) == .orderedSame
                    }
                }
            case let .connection(connectionIdOrUrlPrefix, matchByUrlPrefix):
                rows = try context.fetch(FetchDescriptor<SessionCacheSnapshotRecord>()).filter { row in
                    Self.matchesConnection(
                        row.connectionId,
                        connectionIdOrUrlPrefix: connectionIdOrUrlPrefix,
                        matchByUrlPrefix: matchByUrlPrefix
                    )
                }
            }
            let totalBytes = rows.reduce(Int64(0)) { partial, row in
                partial + Int64(row.payloadJSON.utf8.count)
            }
            return SessionCacheReport(totalBytes: totalBytes, entryCount: Int64(rows.count))
        } catch {
            DiagnosticLog.warning(.session, "SwiftData session cache report failed: \(error.localizedDescription)")
            return SessionCacheReport(totalBytes: 0, entryCount: 0)
        }
    }

    private static func matchesConnection(
        _ connectionId: String,
        connectionIdOrUrlPrefix: String,
        matchByUrlPrefix: Bool
    ) -> Bool {
        if matchByUrlPrefix {
            return connectionId.lowercased().hasPrefix(connectionIdOrUrlPrefix.lowercased())
        }
        return connectionId.caseInsensitiveCompare(connectionIdOrUrlPrefix) == .orderedSame
    }

    private func fetch(cacheKey: String, in context: ModelContext) throws -> SessionCacheSnapshotRecord? {
        var descriptor = FetchDescriptor<SessionCacheSnapshotRecord>(
            predicate: #Predicate { $0.cacheKey == cacheKey }
        )
        descriptor.fetchLimit = 1
        return try context.fetch(descriptor).first
    }
}
