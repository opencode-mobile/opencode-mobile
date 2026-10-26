import Foundation

/// Mime normalization for attachments, shared with Android through
/// `shared/attachment-mime/attachment-mime-cases.json` (#644).
///
/// png/jpeg/gif/webp and pdf keep their type. `text/*` and a few text-like application types
/// become `text/plain`. Anything else is sniffed: a NUL byte in the first 4 KB means binary and
/// keeps the declared type (`application/octet-stream` when none), no NUL means text.
nonisolated enum AttachmentMime {
    static let sniffByteCount = 4096
    static let binaryFallback = "application/octet-stream"

    private static let keptTypes: Set<String> = [
        "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf",
    ]
    private static let textLikeTypes: Set<String> = [
        "application/json", "application/xml", "application/yaml", "application/javascript", "application/x-sh",
    ]

    static func normalized(declared: String?, leadingBytes: Data) -> String {
        let declared = clean(declared)
        if let declared {
            if keptTypes.contains(declared) { return declared }
            if declared.hasPrefix("text/") || textLikeTypes.contains(declared) { return "text/plain" }
        }
        let sniffed = leadingBytes.prefix(sniffByteCount)
        if sniffed.contains(0) { return declared ?? binaryFallback }
        return "text/plain"
    }

    /// The declared mime, or one resolved from a media extension when the picker gave none
    /// (or only the generic octet-stream).
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

    /// Image types the server accepts inline, and PDF.
    static func isNativeMedia(_ mime: String) -> Bool {
        let kind = kind(for: mime)
        return kind == .image || kind == .pdf
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
