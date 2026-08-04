import Foundation
import Testing
@testable import OpenCode_Mobile

struct DiagnosticLogBufferTests {
    @Test func evictsEntriesOlderThanFiveMinutes() {
        var now: Int64 = 10_000_000
        let buffer = DiagnosticLogBuffer(clock: { now })

        buffer.append(area: .sse, level: .info, message: "old")
        now += DiagnosticLogBuffer.windowMilliseconds + 1
        buffer.append(area: .sse, level: .info, message: "new")

        let snapshot = buffer.snapshot()
        #expect(snapshot.entries.map(\.message) == ["new"])
        #expect(snapshot.windowMinutes == 5)
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
        #expect(text.contains(#""windowMinutes":5"#))
        #expect(text.contains(#""area":"sse""#))
        #expect(!text.contains("nope"))
        #expect(text.contains("[REDACTED]"))
    }
}

private extension Array {
    func single() -> Element {
        precondition(count == 1)
        return self[0]
    }
}
