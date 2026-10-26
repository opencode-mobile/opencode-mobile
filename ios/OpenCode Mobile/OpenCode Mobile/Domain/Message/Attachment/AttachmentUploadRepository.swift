import Foundation

/// A file the server now holds, ready to be named in a prompt.
nonisolated struct UploadedAttachment: Equatable, Sendable {
    let serverPath: String
}

/// How an upload ended without a path, in product terms.
nonisolated enum AttachmentUploadError: Error, Equatable, Sendable {
    /// The route no longer matches the contract; the caller sends the file the old way.
    case contractMiss
    /// Timeout, lost connection, server or auth failure.
    case failed
    /// The user cancelled.
    case cancelled
}

nonisolated protocol AttachmentUploadRepository: Sendable {
    /// Streamed upload is on for the active connection (read once per connection, default off).
    func isAvailable() async -> Bool
    func upload(
        fileURL: URL,
        filename: String,
        directory: String?,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws -> UploadedAttachment
}

/// Builds the server path of an upload and turns the route's answer into product outcomes.
nonisolated struct AttachmentUploadDomainAdapter: Sendable {
    /// `<tmp>/uploads/<lowercase uuid>/<filename>`; one directory per file keeps names from colliding.
    func serverPath(tmp: String, filename: String, uuid: UUID = UUID()) -> String {
        let separator = tmp.contains("\\") && !tmp.contains("/") ? "\\" : "/"
        let base = tmp.hasSuffix(separator) ? String(tmp.dropLast()) : tmp
        let safeName = filename.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "\\", with: "_")
        return [base, "uploads", uuid.uuidString.lowercased(), safeName].joined(separator: separator)
    }

    func result(from http: AttachmentUploadHTTPResult) throws -> UploadedAttachment {
        let path = Self.resolvedPath(in: http.body)
        if let failure = AttachmentUploadFailure.classify(statusCode: http.statusCode, hasPath: path != nil) {
            throw failure == .contractMiss ? AttachmentUploadError.contractMiss : AttachmentUploadError.failed
        }
        return UploadedAttachment(serverPath: path ?? "")
    }

    func error(from error: Error) -> AttachmentUploadError {
        if error is CancellationError { return .cancelled }
        if let urlError = error as? URLError, urlError.code == .cancelled { return .cancelled }
        if let known = error as? AttachmentUploadError { return known }
        return .failed
    }

    /// `data.path` of `{ location, data: { path } }`.
    static func resolvedPath(in body: Data) -> String? {
        guard let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let data = object["data"] as? [String: Any],
              let path = data["path"] as? String,
              !path.isEmpty
        else { return nil }
        return path
    }
}

nonisolated final class DefaultAttachmentUploadRepository: AttachmentUploadRepository, @unchecked Sendable {
    private let api: any AttachmentUploadAPI
    private let adapter: AttachmentUploadDomainAdapter
    private let activeConnectionManager: ActiveConnectionManager

    init(
        api: any AttachmentUploadAPI,
        adapter: AttachmentUploadDomainAdapter = AttachmentUploadDomainAdapter(),
        activeConnectionManager: ActiveConnectionManager
    ) {
        self.api = api
        self.adapter = adapter
        self.activeConnectionManager = activeConnectionManager
    }

    func isAvailable() async -> Bool {
        // The first read probes the server's API contract; later reads are the cached answer.
        _ = await activeConnectionManager.currentProtocolOrProbe()
        return activeConnectionManager.capabilities.supportsStreamedUpload
            && activeConnectionManager.streamedUploadDirectory != nil
    }

    func upload(
        fileURL: URL,
        filename: String,
        directory: String?,
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws -> UploadedAttachment {
        guard let tmp = activeConnectionManager.streamedUploadDirectory else {
            throw AttachmentUploadError.contractMiss
        }
        let path = adapter.serverPath(tmp: tmp, filename: filename)
        let http: AttachmentUploadHTTPResult
        do {
            http = try await api.write(fileURL: fileURL, path: path, directory: directory) { sent, total in
                guard total > 0 else { return }
                onProgress(min(1, max(0, Double(sent) / Double(total))))
            }
        } catch {
            throw adapter.error(from: error)
        }
        do {
            return try adapter.result(from: http)
        } catch AttachmentUploadError.contractMiss {
            activeConnectionManager.disableStreamedUpload()
            throw AttachmentUploadError.contractMiss
        }
    }
}
