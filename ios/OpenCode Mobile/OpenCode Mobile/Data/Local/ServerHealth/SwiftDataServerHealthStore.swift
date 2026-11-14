import Foundation
import SwiftData

@Model
final class ServerHealthPingRecord {
    var profileID: String
    var atMs: Int64
    var latencyMs: Int?
    var failure: String?
    var pid: Int?
    var version: String?

    init(profileID: String, atMs: Int64, latencyMs: Int?, failure: String?, pid: Int?, version: String?) {
        self.profileID = profileID
        self.atMs = atMs
        self.latencyMs = latencyMs
        self.failure = failure
        self.pid = pid
        self.version = version
    }
}

@Model
final class ServerHealthEventRecord {
    var profileID: String
    var atMs: Int64
    var kind: String
    var value: String
    var detail: String?

    init(profileID: String, atMs: Int64, kind: String, value: String, detail: String?) {
        self.profileID = profileID
        self.atMs = atMs
        self.kind = kind
        self.value = value
        self.detail = detail
    }
}

/// Server Health record in its own SwiftData store (`server_health`), apart from the session cache.
nonisolated final class SwiftDataServerHealthStore: ServerHealthStore, @unchecked Sendable {
    private let container: ModelContainer
    /// Serialises writes so appends from the monitor and purges from settings do not interleave.
    private let lock = NSLock()

    init(container: ModelContainer) {
        self.container = container
    }

    private static var schema: Schema {
        Schema([ServerHealthPingRecord.self, ServerHealthEventRecord.self])
    }

    static func makePersistentContainer() throws -> ModelContainer {
        let schema = schema
        // Same protection class as the session cache: SQLite reopens its -wal/-shm files on
        // background saves, which fails under `completeUnlessOpen` once the device locks.
        let protection = ProtectedStorage.backgroundProtection
        let directory = try ProtectedStorage.directory("ServerHealth", protection: protection)
        let url = directory.appendingPathComponent("server_health.store", isDirectory: false)
        let configuration = ModelConfiguration("server_health", schema: schema, url: url)
        let container = try ModelContainer(for: schema, configurations: [configuration])
        for suffix in ["", "-wal", "-shm"] {
            ProtectedStorage.protect(file: URL(fileURLWithPath: url.path + suffix), protection: protection)
        }
        return container
    }

    static func makeInMemoryContainer() throws -> ModelContainer {
        let schema = schema
        let configuration = ModelConfiguration("server_health_memory", schema: schema, isStoredInMemoryOnly: true)
        return try ModelContainer(for: schema, configurations: [configuration])
    }

    func appendPing(_ ping: ServerHealthPing) async {
        write("append ping") { context in
            context.insert(ServerHealthPingRecord(
                profileID: ping.profileID,
                atMs: ping.atMs,
                latencyMs: ping.latencyMs,
                failure: ping.failure?.rawValue,
                pid: ping.pid,
                version: ping.version
            ))
        }
    }

    func appendEvent(_ event: ServerHealthEvent) async {
        write("append event") { context in
            context.insert(ServerHealthEventRecord(
                profileID: event.profileID,
                atMs: event.atMs,
                kind: event.kind.rawValue,
                value: event.value,
                detail: event.detail
            ))
        }
    }

    func pings(profileID: String, sinceMs: Int64) async -> [ServerHealthPing] {
        read("pings", fallback: []) { context in
            let descriptor = FetchDescriptor<ServerHealthPingRecord>(
                predicate: #Predicate { $0.profileID == profileID && $0.atMs >= sinceMs },
                sortBy: [SortDescriptor(\.atMs)]
            )
            return try context.fetch(descriptor).map(Self.ping)
        }
    }

    func events(profileID: String, sinceMs: Int64) async -> [ServerHealthEvent] {
        read("events", fallback: []) { context in
            let stateKind = ServerHealthEventKind.state.rawValue
            var before = FetchDescriptor<ServerHealthEventRecord>(
                predicate: #Predicate { $0.profileID == profileID && $0.kind == stateKind && $0.atMs < sinceMs },
                sortBy: [SortDescriptor(\.atMs, order: .reverse)]
            )
            before.fetchLimit = 1
            let after = FetchDescriptor<ServerHealthEventRecord>(
                predicate: #Predicate { $0.profileID == profileID && $0.atMs >= sinceMs },
                sortBy: [SortDescriptor(\.atMs)]
            )
            let initial = try context.fetch(before).compactMap(Self.event)
            return initial + (try context.fetch(after).compactMap(Self.event))
        }
    }

    func lastPid(profileID: String) async -> ServerHealthProcessIdentity? {
        read("last pid", fallback: nil) { context in
            var descriptor = FetchDescriptor<ServerHealthPingRecord>(
                predicate: #Predicate { $0.profileID == profileID && ($0.pid != nil || $0.version != nil) },
                sortBy: [SortDescriptor(\.atMs, order: .reverse)]
            )
            descriptor.fetchLimit = 1
            return try context.fetch(descriptor).first.map { ServerHealthProcessIdentity(pid: $0.pid, version: $0.version) }
        }
    }

    func prune(olderThanMs: Int64) async {
        write("prune") { context in
            try context.delete(model: ServerHealthPingRecord.self, where: #Predicate { $0.atMs < olderThanMs })
            try context.delete(model: ServerHealthEventRecord.self, where: #Predicate { $0.atMs < olderThanMs })
        }
    }

    func purge(profileID: String) async {
        write("purge") { context in
            try context.delete(model: ServerHealthPingRecord.self, where: #Predicate { $0.profileID == profileID })
            try context.delete(model: ServerHealthEventRecord.self, where: #Predicate { $0.profileID == profileID })
        }
    }

    func purgeAll() async {
        write("purge all") { context in
            try context.delete(model: ServerHealthPingRecord.self)
            try context.delete(model: ServerHealthEventRecord.self)
        }
    }

    private func write(_ label: String, _ body: (ModelContext) throws -> Void) {
        lock.lock()
        defer { lock.unlock() }
        do {
            let context = ModelContext(container)
            context.autosaveEnabled = false
            try body(context)
            try context.save()
        } catch {
            DiagnosticLog.warning(.connection, "Server health store \(label) failed: \(error.localizedDescription)")
        }
    }

    private func read<T>(_ label: String, fallback: T, _ body: (ModelContext) throws -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        do {
            return try body(ModelContext(container))
        } catch {
            DiagnosticLog.warning(.connection, "Server health store \(label) failed: \(error.localizedDescription)")
            return fallback
        }
    }

    private static func ping(_ record: ServerHealthPingRecord) -> ServerHealthPing {
        ServerHealthPing(
            profileID: record.profileID,
            atMs: record.atMs,
            latencyMs: record.latencyMs,
            failure: record.failure.flatMap(ServerHealthFailureCategory.init(rawValue:)),
            pid: record.pid,
            version: record.version
        )
    }

    private static func event(_ record: ServerHealthEventRecord) -> ServerHealthEvent? {
        guard let kind = ServerHealthEventKind(rawValue: record.kind) else { return nil }
        return ServerHealthEvent(
            profileID: record.profileID,
            atMs: record.atMs,
            kind: kind,
            value: record.value,
            detail: record.detail
        )
    }
}
