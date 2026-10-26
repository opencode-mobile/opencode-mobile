import Foundation
import UniformTypeIdentifiers

enum AttachmentLoadError: LocalizedError, Equatable {
    case unsupportedType
    case unreadableFile
    case invalidDataURL
    case tooLarge

    var errorDescription: String? {
        switch self {
        case .unsupportedType:
            "Only images, PDFs, or text files can be attached here."
        case .unreadableFile:
            "The attachment could not be read."
        case .invalidDataURL:
            "The attachment data is invalid."
        case .tooLarge:
            AttachmentLimits.tooLargeMessage
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

    nonisolated static func mime(fromFilename filename: String) -> String? {
        let ext = URL(fileURLWithPath: filename).pathExtension.lowercased()
        guard !ext.isEmpty else { return nil }
        switch ext {
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "pdf": return "application/pdf"
        case "txt": return "text/plain"
        default: return nil
        }
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
        guard Int64(data.count) <= AttachmentLimits.maxDecodedBytes else {
            throw AttachmentLoadError.tooLarge
        }
        let mime = try resolvedMime(filename: filename, declaredMime: declaredMime)
        guard let kind = AttachmentDataURL.kind(for: mime) else { throw AttachmentLoadError.invalidDataURL }
        let normalizedFilename = filename.trimmingCharacters(in: .whitespacesAndNewlines)
        let stored = try persist(data: data, filename: normalizedFilename)
        return Attachment(
            id: UUID().uuidString,
            filename: normalizedFilename.isEmpty ? "Attachment" : normalizedFilename,
            mime: mime,
            kind: kind,
            dataURL: "",
            fileURL: stored,
            byteCount: Int64(data.count),
            previewData: kind == .image ? AttachmentPreview.make(data: data) : nil
        )
    }

    nonisolated func load(fileURL: URL, declaredMime: String? = nil) throws -> Attachment {
        let accessed = fileURL.startAccessingSecurityScopedResource()
        defer { if accessed { fileURL.stopAccessingSecurityScopedResource() } }
        do {
            let values = try fileURL.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey])
            if let size = values.fileSize, Int64(size) > AttachmentLimits.maxDecodedBytes {
                throw AttachmentLoadError.tooLarge
            }
            return try load(
                data: Data(contentsOf: fileURL, options: [.mappedIfSafe]),
                filename: fileURL.lastPathComponent,
                declaredMime: declaredMime ?? values.contentType?.preferredMIMEType
            )
        } catch let error as AttachmentLoadError {
            throw error
        } catch {
            throw AttachmentLoadError.unreadableFile
        }
    }

    /// Picks the destination from the file's detected mime and size before reading its bytes.
    /// Inline files go through `load`, so they keep its limits and errors; uploads are copied into
    /// the attachment store and carry a pending upload state.
    nonisolated func prepare(
        fileURL: URL,
        declaredMime: String? = nil,
        destination: (_ mime: String, _ byteCount: Int64) -> AttachmentDestination
    ) throws -> Attachment {
        let accessed = fileURL.startAccessingSecurityScopedResource()
        defer { if accessed { fileURL.stopAccessingSecurityScopedResource() } }
        let values: URLResourceValues
        let leading: Data
        do {
            values = try fileURL.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey])
            let handle = try FileHandle(forReadingFrom: fileURL)
            defer { try? handle.close() }
            leading = try handle.read(upToCount: AttachmentMime.sniffByteCount) ?? Data()
        } catch {
            throw AttachmentLoadError.unreadableFile
        }
        let size = Int64(values.fileSize ?? 0)
        let declared = declaredMime ?? values.contentType?.preferredMIMEType
        let mime = AttachmentMime.normalized(
            declared: AttachmentMime.declared(declared, filename: fileURL.lastPathComponent),
            leadingBytes: leading
        )
        guard destination(mime, size) == .upload else {
            return try load(fileURL: fileURL, declaredMime: declaredMime)
        }
        let stored: URL
        do {
            stored = try persistCopy(of: fileURL, filename: fileURL.lastPathComponent)
        } catch {
            throw AttachmentLoadError.unreadableFile
        }
        return uploadAttachment(stored: stored, filename: fileURL.lastPathComponent, mime: mime, byteCount: size)
    }

    nonisolated func prepare(
        data: Data,
        filename: String,
        declaredMime: String?,
        destination: (_ mime: String, _ byteCount: Int64) -> AttachmentDestination
    ) throws -> Attachment {
        let mime = AttachmentMime.normalized(
            declared: AttachmentMime.declared(declaredMime, filename: filename),
            leadingBytes: data.prefix(AttachmentMime.sniffByteCount)
        )
        guard destination(mime, Int64(data.count)) == .upload else {
            return try load(data: data, filename: filename, declaredMime: declaredMime)
        }
        let name = filename.trimmingCharacters(in: .whitespacesAndNewlines)
        let stored: URL
        do {
            stored = try persist(data: data, filename: name)
        } catch {
            throw AttachmentLoadError.unreadableFile
        }
        return uploadAttachment(stored: stored, filename: name, mime: mime, byteCount: Int64(data.count))
    }

    nonisolated private func uploadAttachment(stored: URL, filename: String, mime: String, byteCount: Int64) -> Attachment {
        let kind = AttachmentMime.kind(for: mime)
        return Attachment(
            id: UUID().uuidString,
            filename: filename.isEmpty ? "Attachment" : filename,
            mime: mime,
            kind: kind,
            dataURL: "",
            fileURL: stored,
            byteCount: byteCount,
            previewData: kind == .image ? AttachmentPreview.make(fileURL: stored) : nil,
            upload: .uploading(fraction: 0)
        )
    }

    nonisolated private func persistCopy(of source: URL, filename: String) throws -> URL {
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(AttachmentLimits.storeDirectoryName, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let safeName = filename.replacingOccurrences(of: "/", with: "_")
        let stored = directory.appendingPathComponent("\(UUID().uuidString)-\(safeName.isEmpty ? "attachment" : safeName)")
        try FileManager.default.copyItem(at: source, to: stored)
        return stored
    }

    nonisolated private func persist(data: Data, filename: String) throws -> URL {
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(AttachmentLimits.storeDirectoryName, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let safeName = filename.replacingOccurrences(of: "/", with: "_")
        let stored = directory.appendingPathComponent("\(UUID().uuidString)-\(safeName.isEmpty ? "attachment" : safeName)")
        try data.write(to: stored, options: .atomic)
        return stored
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
