import Foundation

/// Mime helpers for attachments named by `metadata.attachments`.
nonisolated enum AttachmentMime {
    static let binaryFallback = "application/octet-stream"

    /// The declared mime, or one resolved from a media extension when none (or only the generic
    /// octet-stream) was given.
    static func declared(_ mime: String?, filename: String) -> String? {
        let cleaned = clean(mime)
        if let cleaned, cleaned != binaryFallback { return cleaned }
        switch URL(fileURLWithPath: filename).pathExtension.lowercased() {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "pdf": return "application/pdf"
        default: return cleaned
        }
    }

    static func kind(for mime: String) -> AttachmentKind {
        switch mime.lowercased() {
        case "image/png", "image/jpeg", "image/gif", "image/webp": .image
        case "application/pdf": .pdf
        case "text/plain": .text
        default: .file
        }
    }

    private static func clean(_ mime: String?) -> String? {
        guard let value = mime?.split(separator: ";", maxSplits: 1).first
            .map({ $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }),
            !value.isEmpty
        else { return nil }
        return value
    }
}
