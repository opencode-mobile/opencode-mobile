import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct DiagnosticLogAttachmentTests {
    @Test func make_exportsRedactedJSONLAsTextAttachment() throws {
        let buffer = DiagnosticLogBuffer(clock: { 1_700_000_000_000 })
        buffer.append(area: .sse, level: .info, message: "opened password=secret status=200")

        let attachment = try DiagnosticLogAttachment.make(from: buffer)
        let decoded = String(
            data: AttachmentDataURL.decodedData(from: attachment.dataURL, expectedMime: attachment.mime) ?? Data(),
            encoding: .utf8
        ) ?? ""

        #expect(attachment.filename == DiagnosticLogAttachment.filename)
        #expect(attachment.mime == DiagnosticLogAttachment.mime)
        #expect(attachment.kind == .text)
        #expect(decoded.contains(#""type":"meta""#))
        #expect(decoded.contains(#""area":"sse""#))
        #expect(decoded.contains("[REDACTED]"))
        #expect(!decoded.contains("secret"))
    }
}
