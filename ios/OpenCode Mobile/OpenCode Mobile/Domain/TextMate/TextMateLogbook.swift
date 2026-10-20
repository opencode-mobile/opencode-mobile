import Foundation

nonisolated struct NativeTextMateStageSummary: Codable, Hashable, Sendable {
    let stage: String
    let eventCount: Int
    let totalNanoseconds: Int64
    let maximumNanoseconds: Int64
    let successCount: Int
    let failureCount: Int
    let cacheHitCount: Int
    let cacheMissCount: Int
}

nonisolated struct NativeTextMateParseObservation: Codable, Hashable, Sendable {
    let schemaVersion: Int
    let requestedLanguage: String?
    let canonicalLanguage: String?
    let outcome: String
    let messageCharacters: Int
    let fenceCharacters: Int
    let fenceLines: Int
    let fenceIndex: Int
    let fenceCount: Int
    let spanCount: Int
    let backend: String?
    let totalNanoseconds: Int64
    let stages: [NativeTextMateStageSummary]
    let diagnosticKinds: [String]
    let queueWaitNanoseconds: Int64?
    let cancelRequestedToStopNanoseconds: Int64?
    let schedulingReason: String?

    init(
        schemaVersion: Int = 1,
        requestedLanguage: String? = nil,
        canonicalLanguage: String? = nil,
        outcome: String,
        messageCharacters: Int,
        fenceCharacters: Int,
        fenceLines: Int,
        fenceIndex: Int,
        fenceCount: Int,
        spanCount: Int,
        backend: String? = nil,
        totalNanoseconds: Int64,
        stages: [NativeTextMateStageSummary],
        diagnosticKinds: [String],
        queueWaitNanoseconds: Int64? = nil,
        cancelRequestedToStopNanoseconds: Int64? = nil,
        schedulingReason: String? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.requestedLanguage = requestedLanguage
        self.canonicalLanguage = canonicalLanguage
        self.outcome = outcome
        self.messageCharacters = messageCharacters
        self.fenceCharacters = fenceCharacters
        self.fenceLines = fenceLines
        self.fenceIndex = fenceIndex
        self.fenceCount = fenceCount
        self.spanCount = spanCount
        self.backend = backend
        self.totalNanoseconds = totalNanoseconds
        self.stages = stages
        self.diagnosticKinds = diagnosticKinds
        self.queueWaitNanoseconds = queueWaitNanoseconds
        self.cancelRequestedToStopNanoseconds = cancelRequestedToStopNanoseconds
        self.schedulingReason = schedulingReason
    }
}

nonisolated struct TextMateLogbookEntry: Codable, Hashable, Identifiable, Sendable {
    let schemaVersion: Int
    let id: UUID
    let timestampEpochMilliseconds: Int64
    let observation: NativeTextMateParseObservation
}

nonisolated struct TextMateLogbookSnapshot: Sendable {
    let entries: [TextMateLogbookEntry]
    let droppedEntries: Int
    let storageError: String?
}

nonisolated protocol TextMateLogbookRecording: Sendable {
    func submit(_ observations: [NativeTextMateParseObservation])
}

nonisolated final class TextMateLogbook: TextMateLogbookRecording, @unchecked Sendable {
    nonisolated static let shared = TextMateLogbook()

    private struct PendingBatch {
        let generation: UInt64
        let entries: [TextMateLogbookEntry]
    }

    private let writerQueue = DispatchQueue(label: "dev.opencode.textmate-logbook", qos: .utility)
    private let stateLock = NSLock()
    private let fileURL: URL?
    private var pending: [PendingBatch] = []
    private var generation: UInt64 = 0
    private var drainScheduled = false
    private var clearing = false
    private var droppedEntries = 0

    // Accessed only on writerQueue.
    private var initialized = false
    private var persistedEntryCount = 0
    private var storageError: String?

    init(fileURL: URL? = nil) {
        self.fileURL = fileURL ?? Self.defaultFileURL()
    }

    func submit(_ observations: [NativeTextMateParseObservation]) {
        guard !observations.isEmpty else { return }
        let timestamp = Int64(Date().timeIntervalSince1970 * 1_000)
        let entries = observations.map { observation in
            TextMateLogbookEntry(
                schemaVersion: 1,
                id: UUID(),
                timestampEpochMilliseconds: timestamp,
                observation: observation
            )
        }

        stateLock.lock()
        guard pending.count < Self.maxPendingBatches else {
            droppedEntries += entries.count
            stateLock.unlock()
            return
        }
        pending.append(PendingBatch(generation: generation, entries: entries))
        let shouldSchedule = !drainScheduled && !clearing
        if shouldSchedule {
            drainScheduled = true
        }
        stateLock.unlock()

        if shouldSchedule {
            writerQueue.async { [weak self] in
                self?.drainOnWriter()
            }
        }
    }

    func snapshot(limit: Int = maxPersistedEntries) async -> TextMateLogbookSnapshot {
        await withCheckedContinuation { continuation in
            writerQueue.async { [weak self] in
                guard let self else {
                    continuation.resume(
                        returning: TextMateLogbookSnapshot(entries: [], droppedEntries: 0, storageError: "Unavailable")
                    )
                    return
                }
                self.drainOnWriter()
                continuation.resume(returning: self.snapshotOnWriter(limit: limit))
            }
        }
    }

    func clear() async {
        beginClear()

        await withCheckedContinuation { continuation in
            writerQueue.async { [weak self] in
                guard let self else {
                    continuation.resume()
                    return
                }
                self.clearOnWriter()

                self.stateLock.lock()
                self.clearing = false
                let shouldSchedule = !self.pending.isEmpty && !self.drainScheduled
                if shouldSchedule {
                    self.drainScheduled = true
                }
                self.stateLock.unlock()

                if shouldSchedule {
                    self.writerQueue.async { [weak self] in
                        self?.drainOnWriter()
                    }
                }
                continuation.resume()
            }
        }
    }

    private func beginClear() {
        stateLock.lock()
        generation &+= 1
        pending.removeAll(keepingCapacity: true)
        clearing = true
        stateLock.unlock()
    }

    private func drainOnWriter() {
        while true {
            stateLock.lock()
            if clearing {
                drainScheduled = false
                stateLock.unlock()
                return
            }
            guard !pending.isEmpty else {
                drainScheduled = false
                stateLock.unlock()
                return
            }
            let batch = pending.removeFirst()
            let currentGeneration = generation
            stateLock.unlock()

            guard batch.generation == currentGeneration else { continue }
            appendOnWriter(batch.entries)
        }
    }

    private func appendOnWriter(_ entries: [TextMateLogbookEntry]) {
        ensureInitializedOnWriter()
        guard let fileURL else {
            storageError = "ApplicationSupportUnavailable"
            return
        }

        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let data = try Self.encodedLines(entries)
            if !FileManager.default.fileExists(atPath: fileURL.path) {
                try Data().write(to: fileURL, options: .atomic)
            }
            let handle = try FileHandle(forWritingTo: fileURL)
            defer { try? handle.close() }
            try handle.seekToEnd()
            try handle.write(contentsOf: data)
            persistedEntryCount += entries.count
            let fileSize = try fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            if persistedEntryCount > Self.compactAtEntries || fileSize > Self.maxFileBytes {
                try compactOnWriter()
            }
            storageError = nil
        } catch {
            storageError = String(describing: type(of: error))
        }
    }

    private func snapshotOnWriter(limit: Int) -> TextMateLogbookSnapshot {
        ensureInitializedOnWriter()
        let entries: [TextMateLogbookEntry]
        do {
            entries =
                Array(
                    try readValidEntriesOnWriter()
                        .suffix(limit.clamped(to: 1...Self.maxPersistedEntries))
                        .reversed()
                )
        } catch {
            storageError = String(describing: type(of: error))
            entries = []
        }

        stateLock.lock()
        let dropped = droppedEntries
        stateLock.unlock()
        return TextMateLogbookSnapshot(
            entries: entries,
            droppedEntries: dropped,
            storageError: storageError
        )
    }

    private func clearOnWriter() {
        do {
            if let fileURL, FileManager.default.fileExists(atPath: fileURL.path) {
                try FileManager.default.removeItem(at: fileURL)
            }
            persistedEntryCount = 0
            initialized = true
            storageError = nil
            stateLock.lock()
            droppedEntries = 0
            stateLock.unlock()
        } catch {
            storageError = String(describing: type(of: error))
        }
    }

    private func ensureInitializedOnWriter() {
        guard !initialized else { return }
        persistedEntryCount = (try? readValidEntriesOnWriter().count) ?? 0
        initialized = true
    }

    private func compactOnWriter() throws {
        guard let fileURL else { return }
        let retained = try readValidEntriesOnWriter().suffix(Self.maxPersistedEntries)
        try Self.encodedLines(Array(retained)).write(to: fileURL, options: .atomic)
        persistedEntryCount = retained.count
    }

    private func readValidEntriesOnWriter() throws -> [TextMateLogbookEntry] {
        guard let fileURL, FileManager.default.fileExists(atPath: fileURL.path) else { return [] }
        let data = try Data(contentsOf: fileURL)
        let decoder = JSONDecoder()
        return data.split(separator: 0x0A).compactMap { line in
            try? decoder.decode(TextMateLogbookEntry.self, from: Data(line))
        }
    }

    private static func encodedLines(_ entries: [TextMateLogbookEntry]) throws -> Data {
        let encoder = JSONEncoder()
        var output = Data()
        for entry in entries {
            output.append(try encoder.encode(entry))
            output.append(0x0A)
        }
        return output
    }

    private static func defaultFileURL() -> URL? {
        // Backup-excluded and file-protected (see ProtectedStorage).
        guard let debugDirectory = try? ProtectedStorage.directory("Debug") else {
            return nil
        }
        return debugDirectory
            .appendingPathComponent("textmate-logbook-v1.jsonl", isDirectory: false)
    }

    static let maxPersistedEntries = 500
    private static let compactAtEntries = 550
    private static let maxPendingBatches = 64
    private static let maxFileBytes = 2 * 1024 * 1024
}

nonisolated private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}
