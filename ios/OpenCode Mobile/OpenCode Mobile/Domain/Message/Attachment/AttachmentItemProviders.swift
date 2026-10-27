import Foundation
import UIKit
import UniformTypeIdentifiers

/// Pasted and dropped item providers (#870): what each one is, and a readable file for it.
enum AttachmentItemProviders {
    /// What a provider carries, or `nil` for text (a selection, a link), which is never an attachment.
    /// A text *file* has a file name; a text selection does not.
    nonisolated static func kind(contentTypes: [UTType], suggestedName: String?) -> AttachmentDropKind? {
        if contentTypes.contains(where: { $0.conforms(to: .folder) || $0.conforms(to: .directory) }) { return .folder }
        if contentTypes.contains(where: { $0.conforms(to: .image) }) { return .image }
        if contentTypes.contains(where: { $0.conforms(to: .pdf) }) { return .pdf }
        let named = suggestedName.map { !URL(fileURLWithPath: $0).pathExtension.isEmpty } ?? false
        let isFile = named || contentTypes.contains(.fileURL)
        if contentTypes.contains(where: { $0.conforms(to: .text) || $0.conforms(to: .url) }) {
            return isFile ? .text : nil
        }
        return contentTypes.contains(where: { $0.conforms(to: .data) }) ? .file : nil
    }

    nonisolated static func kind(of provider: NSItemProvider) -> AttachmentDropKind? {
        kind(contentTypes: provider.registeredContentTypes, suggestedName: provider.suggestedName)
    }

    /// Images the server takes inline keep their bytes; any other image (HEIC, TIFF) is re-encoded.
    nonisolated private static let inlineImageTypes: [UTType] = [.png, .jpeg, .gif, .webP]

    /// A temporary copy of what `provider` carries, ready for the Files picker's ingestion path.
    /// `unnamedImageName` names an image that arrives with no file name; it is written as PNG.
    /// The caller deletes `directory` once the files are ingested.
    static func copy(
        _ provider: NSItemProvider,
        into directory: URL,
        unnamedImageName: (() -> String)?
    ) async throws -> URL {
        let types = provider.registeredContentTypes
        let name = provider.suggestedName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        if types.contains(where: { $0.conforms(to: .image) }) {
            if name == nil, let unnamedImageName {
                let data = try await loadData(provider, type: .image)
                guard let png = UIImage(data: data)?.pngData() else { throw AttachmentLoadError.unreadableFile }
                return try write(png, named: unnamedImageName(), into: directory)
            }
            if let type = types.first(where: { inlineType in inlineImageTypes.contains { inlineType.conforms(to: $0) } }) {
                return try await copyFile(provider, type: type, named: name, into: directory)
            }
            let data = try await loadData(provider, type: .image)
            guard let jpeg = UIImage(data: data)?.jpegData(compressionQuality: 0.9) else {
                throw AttachmentLoadError.unreadableFile
            }
            let base = name.map { URL(fileURLWithPath: $0).deletingPathExtension().lastPathComponent } ?? "Image"
            return try write(jpeg, named: "\(base).jpg", into: directory)
        }
        if let type = types.first(where: { $0.conforms(to: .data) && !$0.conforms(to: .url) }) {
            return try await copyFile(provider, type: type, named: name, into: directory)
        }
        if types.contains(.fileURL) {
            let source = try await loadFileURL(provider)
            let accessed = source.startAccessingSecurityScopedResource()
            defer { if accessed { source.stopAccessingSecurityScopedResource() } }
            let target = uniqueURL(named: name ?? source.lastPathComponent, in: directory)
            try FileManager.default.copyItem(at: source, to: target)
            return target
        }
        throw AttachmentLoadError.unreadableFile
    }

    private static func copyFile(_ provider: NSItemProvider, type: UTType, named name: String?, into directory: URL) async throws -> URL {
        let fallback = "Attachment" + (type.preferredFilenameExtension.map { ".\($0)" } ?? "")
        var filename = name ?? fallback
        if URL(fileURLWithPath: filename).pathExtension.isEmpty, let ext = type.preferredFilenameExtension {
            filename += ".\(ext)"
        }
        let target = uniqueURL(named: filename, in: directory)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            _ = provider.loadFileRepresentation(for: type, openInPlace: false) { url, _, error in
                // The provided file is removed when this handler returns: copy it now.
                guard let url else {
                    continuation.resume(throwing: error ?? AttachmentLoadError.unreadableFile)
                    return
                }
                do {
                    try FileManager.default.copyItem(at: url, to: target)
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: AttachmentLoadError.unreadableFile)
                }
            }
        }
        return target
    }

    private static func loadData(_ provider: NSItemProvider, type: UTType) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            _ = provider.loadDataRepresentation(for: type) { data, error in
                if let data {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(throwing: error ?? AttachmentLoadError.unreadableFile)
                }
            }
        }
    }

    private static func loadFileURL(_ provider: NSItemProvider) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            _ = provider.loadObject(ofClass: URL.self) { url, error in
                if let url {
                    continuation.resume(returning: url)
                } else {
                    continuation.resume(throwing: error ?? AttachmentLoadError.unreadableFile)
                }
            }
        }
    }

    private static func write(_ data: Data, named name: String, into directory: URL) throws -> URL {
        let target = uniqueURL(named: name, in: directory)
        try data.write(to: target, options: .atomic)
        return target
    }

    /// Each item gets its own folder so two items with one name keep that name.
    private static func uniqueURL(named name: String, in directory: URL) -> URL {
        let folder = directory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent(name.replacingOccurrences(of: "/", with: "_"))
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
