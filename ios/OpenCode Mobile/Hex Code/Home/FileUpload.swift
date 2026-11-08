import Foundation
import OpenCodeKit
import UniformTypeIdentifiers

/// Uploads a file's exact bytes to the computer (app plan, "Attach files (M7)" › Upload); a fake in
/// tests. Returns where the server put it.
protocol FileUploading {
    func upload(file: URL, name: String, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> String
}

/// How an upload ended without a path.
enum FileUploadError: Error, Equatable {
    /// `400`, `404`, `405` or `415`: the route has changed; Files is dimmed for this connection.
    case routeChanged
    /// A server error, a lost connection or an answer without `data.path`.
    case failed
}

/// `POST /api/experimental/fs/write?location[directory]=<today's project>&path=<paths.tmp>/uploads/<uuid>/<name>`,
/// streamed from the cached copy through OpenCodeKit's `AttachmentUploadAPIClient`. Always
/// `location[directory]`, never `directory=`, which the server ignores.
struct ServerFileUploader: FileUploading {
    let api: any AttachmentUploadAPI
    /// The server's `paths.tmp`.
    let tmp: String
    /// Today's project.
    let directory: () -> String

    func upload(file: URL, name: String, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> String {
        let path = AttachedFiles.serverPath(tmp: tmp, name: name)
        let answer = try await api.write(fileURL: file, path: path, directory: directory(), onProgress: onProgress)
        return try Self.path(from: answer)
    }

    /// `data.path` of a `200`; the statuses OpenCode Mobile reads as a changed route throw
    /// `routeChanged`, anything else `failed`.
    static func path(from answer: AttachmentUploadHTTPResult) throws -> String {
        if [400, 404, 405, 415].contains(answer.statusCode) { throw FileUploadError.routeChanged }
        guard (200 ..< 300).contains(answer.statusCode),
              let object = try? JSONSerialization.jsonObject(with: answer.body) as? [String: Any],
              let data = object["data"] as? [String: Any],
              let path = data["path"] as? String, !path.isEmpty
        else { throw FileUploadError.failed }
        return path
    }
}

/// A file picked in the Attach sheet, from Files or a video from Photos and videos. `copy` puts
/// its bytes into the app's cache, off the main thread; the upload streams from there.
nonisolated struct PickedFile: Sendable {
    let name: String
    let mime: String
    let kind: DraftFile.Kind
    let copy: @Sendable () async throws -> URL

    init(name: String, mime: String, kind: DraftFile.Kind = .file, copy: @escaping @Sendable () async throws -> URL) {
        self.name = name
        self.mime = mime
        self.kind = kind
        self.copy = copy
    }

    struct Unreadable: Error {}

    /// The app's cache folder for picked files, beside the prepared images: emptied at launch, and
    /// each copy is deleted once its upload is done.
    static var cacheFolder: URL { ImagePreparer.cacheFolder.appending(path: "Files", directoryHint: .isDirectory) }

    /// A file from Files: read through its security-scoped URL and copied into the cache now, as
    /// OpenCode Mobile does.
    init(url: URL, in folder: URL = PickedFile.cacheFolder) {
        let values = try? url.resourceValues(forKeys: [.contentTypeKey, .localizedNameKey, .nameKey])
        let name = url.lastPathComponent.isEmpty ? (values?.name ?? "") : url.lastPathComponent
        self.init(name: name, mime: Self.mime(values?.contentType ?? UTType(filenameExtension: url.pathExtension))) {
            try await Task.detached(priority: .userInitiated) {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                return try Self.copyIntoCache(url, name: name, folder: folder)
            }.value
        }
    }

    /// Copies `source` to `<folder>/<uuid>/<name>`.
    static func copyIntoCache(_ source: URL, name: String, folder: URL = PickedFile.cacheFolder) throws -> URL {
        let directory = folder.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = directory.appending(path: AttachedFiles.fileName(name))
        var isFolder: ObjCBool = false
        guard FileManager.default.fileExists(atPath: source.path, isDirectory: &isFolder), !isFolder.boolValue else {
            throw Unreadable()
        }
        try FileManager.default.copyItem(at: source, to: target)
        return target
    }

    static func mime(_ type: UTType?) -> String {
        type?.preferredMIMEType ?? AttachedFiles.fallbackMime
    }

    /// The cached copy and its folder go.
    static func removeCopy(_ file: URL) {
        try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
    }
}

extension HomeModel {
    /// The uploader for a server that declares the upload route and `paths.tmp`; nil otherwise.
    static func uploader(features: V2ServerFeatures, connection: @escaping @Sendable () -> ServerConnection?, directory: @escaping () -> String) -> (any FileUploading)? {
        guard features.streamedUpload, let tmp = features.uploadDirectory else { return nil }
        return ServerFileUploader(api: AttachmentUploadAPIClient(connectionProvider: connection), tmp: tmp, directory: directory)
    }
}
