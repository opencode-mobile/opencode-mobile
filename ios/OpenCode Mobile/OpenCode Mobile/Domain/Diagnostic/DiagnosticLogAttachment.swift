import Foundation

enum DiagnosticLogAttachment {
    static let filename = "opencode-diagnostic-logs.jsonl"
    static let mime = "text/plain"

    static func make(from buffer: DiagnosticLogBuffer = .shared) throws -> Attachment {
        try AttachmentLoader().load(
            data: try buffer.exportJSONL(),
            filename: filename,
            declaredMime: mime
        )
    }
}
