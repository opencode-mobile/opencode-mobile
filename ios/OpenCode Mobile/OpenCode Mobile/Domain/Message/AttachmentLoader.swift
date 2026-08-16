import Foundation
import UniformTypeIdentifiers

enum AttachmentLoadError: LocalizedError, Equatable {
    case unsupportedType
    case unreadableFile
    case invalidDataURL

    var errorDescription: String? {
        switch self {
        case .unsupportedType:
            "Only images, PDFs, or text files can be attached here."
        case .unreadableFile:
            "The attachment could not be read."
        case .invalidDataURL:
            "The attachment data is invalid."
        }
    }
}

enum AttachmentDataURL {
    nonisolated private static let imageMimes: Set<String> = ["image/png", "image/jpeg", "image/gif", "image/webp"]

    nonisolated static func kind(for mime: String) -> AttachmentKind? {
        let normalized = mime.lowercased()
        if imageMimes.contains(normalized) { return .image }
        if normalized == "application/pdf" { return .pdf }
        if normalized == "text/plain" { return .text }
        return nil
    }

    nonisolated static func make(data: Data, mime: String) -> String? {
        guard kind(for: mime) != nil else { return nil }
        return "data:\(mime.lowercased());base64,\(data.base64EncodedString())"
    }

    nonisolated static func decodedData(from value: String, expectedMime: String) -> Data? {
        let prefix = "data:\(expectedMime.lowercased());base64,"
        guard value.lowercased().hasPrefix(prefix.lowercased()) else { return nil }
        return Data(base64Encoded: String(value.dropFirst(prefix.count)))
    }

    nonisolated static func validates(_ value: String, mime: String) -> Bool {
        kind(for: mime) != nil && decodedData(from: value, expectedMime: mime) != nil
    }

    nonisolated static func mime(fromDataURL value: String) -> String? {
        guard value.lowercased().hasPrefix("data:") else { return nil }
        let header = value.dropFirst(5).split(separator: ",", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let mime = header.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init)?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        guard !mime.isEmpty, kind(for: mime) != nil else { return nil }
        return mime
    }
}

struct AttachmentLoader: Sendable {
    nonisolated private static let textExtensions: Set<String> = [
        "c", "cc", "conf", "cpp", "css", "csv", "go", "h", "hpp", "htm", "html", "java", "js",
        "json", "jsonl", "jsx", "kt", "kts", "log", "markdown", "md", "mjs", "mm", "py", "rb",
        "rs", "sh", "sql", "swift", "toml", "ts", "tsx", "txt", "xml", "yaml", "yml", "zsh",
    ]

    nonisolated init() {}

    nonisolated func load(data: Data, filename: String, declaredMime: String?) throws -> Attachment {
        let mime = try resolvedMime(filename: filename, declaredMime: declaredMime)
        guard let kind = AttachmentDataURL.kind(for: mime),
              let dataURL = AttachmentDataURL.make(data: data, mime: mime)
        else { throw AttachmentLoadError.invalidDataURL }
        let normalizedFilename = filename.trimmingCharacters(in: .whitespacesAndNewlines)
        return Attachment(
            id: UUID().uuidString,
            filename: normalizedFilename.isEmpty ? "Attachment" : normalizedFilename,
            mime: mime,
            kind: kind,
            dataURL: dataURL
        )
    }

    nonisolated func load(fileURL: URL, declaredMime: String? = nil) throws -> Attachment {
        let accessed = fileURL.startAccessingSecurityScopedResource()
        defer { if accessed { fileURL.stopAccessingSecurityScopedResource() } }
        do {
            return try load(
                data: Data(contentsOf: fileURL, options: [.mappedIfSafe]),
                filename: fileURL.lastPathComponent,
                declaredMime: declaredMime ?? (try? fileURL.resourceValues(forKeys: [.contentTypeKey]).contentType?.preferredMIMEType)
            )
        } catch let error as AttachmentLoadError {
            throw error
        } catch {
            throw AttachmentLoadError.unreadableFile
        }
    }

    nonisolated private func resolvedMime(filename: String, declaredMime: String?) throws -> String {
        let mime = declaredMime?
            .lowercased()
            .split(separator: ";", maxSplits: 1)
            .first
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        if let mime, AttachmentDataURL.kind(for: mime) != nil { return mime }
        if let mime, mime.hasPrefix("text/") { return "text/plain" }

        switch URL(fileURLWithPath: filename).pathExtension.lowercased() {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "pdf": return "application/pdf"
        case let ext where Self.textExtensions.contains(ext): return "text/plain"
        default: throw AttachmentLoadError.unsupportedType
        }
    }
}
