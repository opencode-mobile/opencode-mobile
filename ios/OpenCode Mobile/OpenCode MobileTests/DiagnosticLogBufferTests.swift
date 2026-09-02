import Foundation
import Testing
@testable import OpenCode_Mobile

struct DiagnosticLogBufferTests {
    @Test func evictsEntriesOlderThanRetainWindow() {
        var now: Int64 = 10_000_000
        let buffer = DiagnosticLogBuffer(clock: { now })

        buffer.append(area: .sse, level: .info, message: "old")
        now += DiagnosticLogBuffer.retainMilliseconds + 1
        buffer.append(area: .sse, level: .info, message: "new")

        let snapshot = buffer.snapshot()
        #expect(snapshot.entries.map(\.message) == ["new"])
        #expect(snapshot.retainMinutes == 60)
    }

    @Test func keepsEntriesAcrossRestartInsideRetainWindow() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("diagnostic-logs-restart-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("diagnostic-logs-v1.jsonl")
        defer { try? FileManager.default.removeItem(at: directory) }

        var now: Int64 = 1_700_000_000_000
        let first = DiagnosticLogBuffer(fileURL: file, clock: { now })
        first.append(area: .sse, level: .info, message: "before restart")
        first.flushPersisted()

        now += 6 * 60 * 1_000
        let reopened = DiagnosticLogBuffer(fileURL: file, clock: { now })
        #expect(reopened.snapshot().entries.map(\.message) == ["before restart"])
    }

    @Test func softCapDropsOldestAndRecordsCount() {
        var now: Int64 = 1_000
        let buffer = DiagnosticLogBuffer(clock: { now })

        for index in 0 ..< (DiagnosticLogBuffer.softCap + 25) {
            now += 1
            buffer.append(area: .session, level: .info, message: "line-\(index)")
        }

        let snapshot = buffer.snapshot()
        #expect(snapshot.entries.count == DiagnosticLogBuffer.softCap)
        #expect(snapshot.droppedBySoftCap == 25)
        #expect(snapshot.entries.first?.message == "line-25")
        #expect(snapshot.entries.last?.message == "line-\(DiagnosticLogBuffer.softCap + 24)")
    }

    @Test func clearResetsEntriesAndDroppedCount() {
        let buffer = DiagnosticLogBuffer(clock: { 42 })
        buffer.append(area: .permission, level: .warning, message: "pending count=1")
        _ = buffer.snapshot()
        buffer.clear()
        let snapshot = buffer.snapshot()
        #expect(snapshot.entries.isEmpty)
        #expect(snapshot.droppedBySoftCap == 0)
    }

    @Test func redactsSecretsAndPreservesSafeStatusLines() {
        #expect(
            DiagnosticLogRedactor.redact("auth failed password=super-secret token=abc123")
                .contains("[REDACTED]")
        )
        #expect(!DiagnosticLogRedactor.redact("password=super-secret").contains("super-secret"))
        #expect(!DiagnosticLogRedactor.redact("Authorization: Basic dXNlcjpwYXNz").contains("dXNlcjpwYXNz"))
        #expect(!DiagnosticLogRedactor.redact("Bearer eyJhbGciOi").contains("eyJhbGciOi"))

        let safe = DiagnosticLogRedactor.redact("permission refresh count=2 trigger=permission.asked")
        #expect(safe == "permission refresh count=2 trigger=permission.asked")
    }

    @Test func appendRedactsBeforeStorage() {
        let buffer = DiagnosticLogBuffer(clock: { 99 })
        buffer.append(area: .sse, level: .info, message: "opened password=leak status=200")
        let message = buffer.snapshot().entries.single().message
        #expect(!message.contains("leak"))
        #expect(message.contains("[REDACTED]"))
        #expect(message.contains("status=200"))
    }

    @Test func exportJSONLIncludesMetaAndEntriesWithoutSecrets() throws {
        let buffer = DiagnosticLogBuffer(clock: { 1_700_000_000_000 })
        buffer.append(area: .sse, level: .info, message: "GET /global/event opened status=200")
        buffer.append(area: .permission, level: .warning, message: "refresh failed password=nope")

        let data = try buffer.exportJSONL()
        let text = String(data: data, encoding: .utf8) ?? ""
        #expect(text.contains(#""type":"meta""#))
        #expect(text.contains(#""windowMinutes":15"#))
        #expect(text.contains(#""retainMinutes":60"#))
        #expect(text.contains(#""exportWindowMinutes":15"#))
        #expect(text.contains(#""area":"sse""#))
        #expect(!text.contains("nope"))
        #expect(text.contains("[REDACTED]"))
    }

    @Test func appendDoesNotPersistSynchronously() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("diagnostic-logs-async-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("diagnostic-logs-v1.jsonl")
        defer { try? FileManager.default.removeItem(at: directory) }

        let buffer = DiagnosticLogBuffer(fileURL: file, clock: { 99 }, persistDelayMilliseconds: nil)
        for index in 0 ..< 50 {
            buffer.append(area: .sse, level: .info, message: "delta-\(index)")
        }
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(buffer.snapshot().entries.count == 50)

        buffer.flushPersisted()
        #expect(FileManager.default.fileExists(atPath: file.path))
        let reopened = DiagnosticLogBuffer(fileURL: file, clock: { 99 })
        #expect(reopened.snapshot().entries.count == 50)
    }

    @Test func persistsAcrossRelaunchWithinWindow() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("diagnostic-logs-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("diagnostic-logs-v1.jsonl")
        defer { try? FileManager.default.removeItem(at: directory) }

        var now: Int64 = 1_700_000_000_000
        let first = DiagnosticLogBuffer(fileURL: file, clock: { now })
        first.append(area: .sse, level: .info, message: "before restart")
        first.append(area: .session, level: .warning, message: "busy stuck")
        first.flushPersisted()

        let reopened = DiagnosticLogBuffer(fileURL: file, clock: { now })
        #expect(reopened.snapshot().entries.map(\.message) == ["before restart", "busy stuck"])
        #expect(FileManager.default.fileExists(atPath: file.path))

        now += DiagnosticLogBuffer.retainMilliseconds + 1
        let expired = DiagnosticLogBuffer(fileURL: file, clock: { now })
        #expect(expired.snapshot().entries.isEmpty)
    }

    @Test func exportJSONLFiltersToChosenWindow() throws {
        var now: Int64 = 1_700_000_000_000
        let buffer = DiagnosticLogBuffer(clock: { now })
        buffer.append(area: .sse, level: .info, message: "old")
        now += 20 * 60 * 1_000
        buffer.append(area: .sse, level: .info, message: "recent")

        let fifteen = String(data: try buffer.exportJSONL(window: .fifteenMinutes), encoding: .utf8) ?? ""
        #expect(fifteen.contains("recent"))
        #expect(!fifteen.contains(#""message":"old""#))
        #expect(fifteen.contains(#""exportWindowMinutes":15"#))

        let sixty = String(data: try buffer.exportJSONL(window: .sixtyMinutes), encoding: .utf8) ?? ""
        #expect(sixty.contains("old"))
        #expect(sixty.contains("recent"))
        #expect(sixty.contains(#""exportWindowMinutes":60"#))
    }

    @Test func clearRemovesPersistedFile() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("diagnostic-logs-clear-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("diagnostic-logs-v1.jsonl")
        defer { try? FileManager.default.removeItem(at: directory) }

        let buffer = DiagnosticLogBuffer(fileURL: file, clock: { 99 })
        buffer.append(area: .permission, level: .info, message: "pending count=1")
        buffer.flushPersisted()
        #expect(FileManager.default.fileExists(atPath: file.path))

        buffer.clear()
        #expect(buffer.snapshot().entries.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }
}

private extension Array {
    func single() -> Element {
        precondition(count == 1)
        return self[0]
    }
}
