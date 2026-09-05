import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct DiagnosticLogAttachmentTests {
    @Test func make_exportsRedactedJSONLAsTextAttachment() throws {
        let buffer = DiagnosticLogBuffer(clock: { 1_700_000_000_000 })
        buffer.append(area: .sse, level: .info, message: "opened password=secret status=200")

        let attachment = try DiagnosticLogAttachment.make(from: buffer)
        let decoded = String(data: try Data(contentsOf: try #require(attachment.fileURL)), encoding: .utf8) ?? ""

        #expect(attachment.filename == DiagnosticLogAttachment.filename)
        #expect(attachment.mime == DiagnosticLogAttachment.mime)
        #expect(attachment.kind == .text)
        #expect(decoded.contains(#""type":"meta""#))
        #expect(decoded.contains(#""area":"sse""#))
        #expect(decoded.contains("[REDACTED]"))
        #expect(!decoded.contains("secret"))
    }

    @Test func discard_deletesOwnedPendingFileAndIgnoresForeignPaths() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(AttachmentLimits.storeDirectoryName, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let owned = directory.appendingPathComponent("pending.bin")
        try Data([1, 2, 3]).write(to: owned)
        let foreign = FileManager.default.temporaryDirectory.appendingPathComponent("foreign.bin")
        try Data([4]).write(to: foreign)

        AttachmentLimits.discard(owned)
        AttachmentLimits.discard(foreign)

        #expect(!FileManager.default.fileExists(atPath: owned.path))
        #expect(FileManager.default.fileExists(atPath: foreign.path))
        try? FileManager.default.removeItem(at: foreign)
        try? FileManager.default.removeItem(at: directory)
    }
}
