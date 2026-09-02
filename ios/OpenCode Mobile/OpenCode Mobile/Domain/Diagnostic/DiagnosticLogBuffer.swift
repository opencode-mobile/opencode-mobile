import Foundation

nonisolated enum DiagnosticLogArea: String, Codable, Sendable, CaseIterable {
    case sse
    case permission
    case question
    case session
    case chat
    case hang
    case connection
}

nonisolated enum DiagnosticLogLevel: String, Codable, Sendable, CaseIterable {
    case debug
    case info
    case warning
    case error
}

nonisolated struct DiagnosticLogEntry: Identifiable, Codable, Hashable, Sendable {
    let id: UUID
    let timestampEpochMilliseconds: Int64
    let area: DiagnosticLogArea
    let level: DiagnosticLogLevel
    let message: String

    init(
        id: UUID = UUID(),
        timestampEpochMilliseconds: Int64,
        area: DiagnosticLogArea,
        level: DiagnosticLogLevel,
        message: String
    ) {
        self.id = id
        self.timestampEpochMilliseconds = timestampEpochMilliseconds
        self.area = area
        self.level = level
        self.message = message
    }
}

nonisolated struct DiagnosticLogSnapshot: Sendable {
    let entries: [DiagnosticLogEntry]
    let droppedBySoftCap: Int
    let retainMinutes: Int
    let softCap: Int
}

nonisolated enum DiagnosticLogRedactor: Sendable {
    static let maxMessageLength = 500

    private static let patterns: [(NSRegularExpression, String)] = {
        let specs: [(String, String)] = [
            (#"(?i)\bBasic\s+[A-Za-z0-9+/=_-]+"#, "Basic [REDACTED]"),
            (#"(?i)\bBearer\s+\S+"#, "Bearer [REDACTED]"),
            (#"(?i)(password\s*[=:]\s*)\S+"#, "$1[REDACTED]"),
            (#"(?i)(token\s*[=:]\s*)\S+"#, "$1[REDACTED]"),
            (#"(?i)(authorization\s*[=:]\s*).+"#, "$1[REDACTED]"),
            (#"(?i)(OPENCODE_SERVER_PASSWORD\s*[=:]\s*)\S+"#, "$1[REDACTED]"),
        ]
        return specs.compactMap { pattern, template in
            guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
            return (regex, template)
        }
    }()

    static func redact(_ message: String) -> String {
        var result = message
        for (regex, template) in patterns {
            result = regex.stringByReplacingMatches(
                in: result,
                options: [],
                range: NSRange(result.startIndex..<result.endIndex, in: result),
                withTemplate: template
            )
        }
        if result.count > maxMessageLength {
            let end = result.index(result.startIndex, offsetBy: maxMessageLength)
            result = String(result[..<end]) + "…"
        }
        return result
    }
}

nonisolated final class DiagnosticLogBuffer: @unchecked Sendable {
    static let shared = DiagnosticLogBuffer(fileURL: defaultFileURL())

    static let retainMinutes = 60
    static let retainMilliseconds: Int64 = 60 * 60 * 1_000
    static let softCap = 10_000

    private let lock = NSLock()
    private var entries: [DiagnosticLogEntry] = []
    private var droppedBySoftCap = 0
    private let clock: () -> Int64
    private let fileURL: URL?
    private let persistDelayMilliseconds: Int?
    private let persistQueue = DispatchQueue(label: "dev.opencode.mobile.diagnostic-log")
    private var persistGeneration: UInt64 = 0
    private var persistScheduled = false

    init(
        fileURL: URL? = nil,
        clock: @escaping () -> Int64 = {
            Int64(Date().timeIntervalSince1970 * 1_000)
        },
        persistDelayMilliseconds: Int? = 250
    ) {
        self.fileURL = fileURL
        self.clock = clock
        self.persistDelayMilliseconds = persistDelayMilliseconds
        if fileURL != nil {
            loadFromDiskUnlocked()
        }
    }

    func append(area: DiagnosticLogArea, level: DiagnosticLogLevel, message: String) {
        let entry = DiagnosticLogEntry(
            timestampEpochMilliseconds: clock(),
            area: area,
            level: level,
            message: DiagnosticLogRedactor.redact(message)
        )
        lock.lock()
        entries.append(entry)
        evictLocked(now: entry.timestampEpochMilliseconds)
        let shouldSchedule = fileURL != nil && persistDelayMilliseconds != nil && !persistScheduled
        if shouldSchedule {
            persistScheduled = true
        }
        lock.unlock()
        if shouldSchedule {
            schedulePersist()
        }
    }

    func snapshot() -> DiagnosticLogSnapshot {
        lock.lock()
        defer { lock.unlock() }
        let now = clock()
        _ = evictLocked(now: now)
        return DiagnosticLogSnapshot(
            entries: entries,
            droppedBySoftCap: droppedBySoftCap,
            retainMinutes: Self.retainMinutes,
            softCap: Self.softCap
        )
    }

    func flushPersisted() {
        persistQueue.sync { writeOnPersistQueue() }
    }

    func clear() {
        persistQueue.sync {
            self.lock.lock()
            self.entries.removeAll(keepingCapacity: true)
            self.droppedBySoftCap = 0
            self.persistScheduled = false
            self.persistGeneration += 1
            self.lock.unlock()
            self.clearPersistedLocked()
        }
    }

    func exportJSONL(window: DiagnosticLogExportWindow = .default) throws -> Data {
        let snapshot = snapshot()
        let now = clock()
        let cutoff = now - window.milliseconds
        let exported = snapshot.entries.filter { $0.timestampEpochMilliseconds >= cutoff }
        var lines: [String] = [
            try jsonObjectLine([
                "type": "meta",
                "schemaVersion": 1,
                "windowMinutes": window.minutes,
                "retainMinutes": snapshot.retainMinutes,
                "exportWindowMinutes": window.minutes,
                "softCap": snapshot.softCap,
                "droppedBySoftCap": snapshot.droppedBySoftCap,
                "entryCount": exported.count,
                "exportedAtEpochMilliseconds": now,
            ]),
        ]
        for entry in exported {
            lines.append(
                try jsonObjectLine([
                    "type": "entry",
                    "id": entry.id.uuidString,
                    "timestampEpochMilliseconds": entry.timestampEpochMilliseconds,
                    "area": entry.area.rawValue,
                    "level": entry.level.rawValue,
                    "message": entry.message,
                ])
            )
        }
        return Data((lines.joined(separator: "\n") + "\n").utf8)
    }

    @discardableResult
    private func evictLocked(now: Int64) -> Bool {
        let beforeCount = entries.count
        let cutoff = now - Self.retainMilliseconds
        entries.removeAll { $0.timestampEpochMilliseconds < cutoff }
        while entries.count > Self.softCap {
            entries.removeFirst()
            droppedBySoftCap += 1
        }
        return entries.count != beforeCount
    }

    private func loadFromDiskUnlocked() {
        guard let fileURL, FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            let data = try Data(contentsOf: fileURL)
            let decoder = JSONDecoder()
            let loaded = data.split(separator: 0x0A).compactMap { line -> DiagnosticLogEntry? in
                guard !line.isEmpty else { return nil }
                return try? decoder.decode(DiagnosticLogEntry.self, from: Data(line))
            }
            entries = loaded
            droppedBySoftCap = 0
            _ = evictLocked(now: clock())
        } catch {
            // Leave memory empty when the store is unreadable; next append recreates it.
            entries = []
            droppedBySoftCap = 0
        }
    }

    private func schedulePersist() {
        guard let delay = persistDelayMilliseconds else { return }
        persistQueue.asyncAfter(deadline: .now() + .milliseconds(delay)) { [weak self] in
            self?.writeOnPersistQueue()
        }
    }

    private func writeOnPersistQueue() {
        guard fileURL != nil else { return }
        let snapshot: [DiagnosticLogEntry]
        let generation: UInt64
        lock.lock()
        snapshot = entries
        persistScheduled = false
        persistGeneration += 1
        generation = persistGeneration
        lock.unlock()
        writeEntries(snapshot, generation: generation)
    }

    private func writeEntries(_ entries: [DiagnosticLogEntry], generation: UInt64) {
        guard let fileURL else { return }
        do {
            let data = try Self.encodedLines(entries)
            lock.lock()
            let stale = generation != persistGeneration
            lock.unlock()
            if stale { return }
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: fileURL, options: .atomic)
        } catch {
            // Persistence is best-effort; in-memory window remains authoritative for this process.
        }
    }

    private func clearPersistedLocked() {
        guard let fileURL else { return }
        try? FileManager.default.removeItem(at: fileURL)
    }

    private func jsonObjectLine(_ object: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        guard let line = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileWriteInapplicableStringEncoding)
        }
        return line
    }

    private static func encodedLines(_ entries: [DiagnosticLogEntry]) throws -> Data {
        let encoder = JSONEncoder()
        var output = Data()
        for entry in entries {
            output.append(try encoder.encode(entry))
            output.append(0x0A)
        }
        return output
    }

    static func defaultFileURL() -> URL? {
        guard let applicationSupport =
            try? FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
        else {
            return nil
        }
        return applicationSupport
            .appendingPathComponent("OpenCodeMobile", isDirectory: true)
            .appendingPathComponent("Debug", isDirectory: true)
            .appendingPathComponent("diagnostic-logs-v1.jsonl", isDirectory: false)
    }
}
