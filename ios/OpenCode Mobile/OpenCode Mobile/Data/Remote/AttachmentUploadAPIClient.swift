import Foundation

/// The raw answer of `POST /api/experimental/fs/write`; the domain adapter classifies it.
nonisolated struct AttachmentUploadHTTPResult: Sendable, Equatable {
    let statusCode: Int
    let body: Data
}

nonisolated protocol AttachmentUploadAPI: Sendable {
    /// Streams `fileURL` as `application/octet-stream` to `path` (relative to, or absolute on, the
    /// server). Any HTTP status comes back as a result; transport failures and cancellation throw.
    func write(
        fileURL: URL,
        path: String,
        directory: String?,
        onProgress: @escaping @Sendable (Int64, Int64) -> Void
    ) async throws -> AttachmentUploadHTTPResult
}

nonisolated enum AttachmentUploadAPIError: Error {
    case noActiveConnection
    case invalidResponse
}

nonisolated struct AttachmentUploadAPIClient: AttachmentUploadAPI {
    static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        // Per-request inactivity timeout: a stalled upload fails instead of hanging the chip.
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 60 * 60
        configuration.waitsForConnectivity = false
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration, delegate: RedirectPolicyDelegate.shared, delegateQueue: nil)
    }()

    private let connectionProvider: @Sendable () -> ServerConnection?
    private let urlSession: URLSession

    init(connectionProvider: @escaping @Sendable () -> ServerConnection?, urlSession: URLSession = Self.session) {
        self.connectionProvider = connectionProvider
        self.urlSession = urlSession
    }

    func write(
        fileURL: URL,
        path: String,
        directory: String?,
        onProgress: @escaping @Sendable (Int64, Int64) -> Void
    ) async throws -> AttachmentUploadHTTPResult {
        guard let connection = connectionProvider() else { throw AttachmentUploadAPIError.noActiveConnection }
        var request = connection.authorizedRequest(
            path: "api/experimental/fs/write",
            method: "POST",
            locationDirectory: directory
        )
        request.url = request.url.flatMap { Self.appendingPath(path, to: $0) }
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let startedAt = OpenCodeAPITrace.start(request)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await urlSession.upload(
                for: request,
                fromFile: fileURL,
                delegate: ProgressRelay(onProgress: onProgress)
            )
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw error
        }
        guard let http = response as? HTTPURLResponse else {
            OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
            throw AttachmentUploadAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        return AttachmentUploadHTTPResult(statusCode: http.statusCode, body: data)
    }

    /// `path` goes in the query with only unreserved characters left bare, so a `+`, `&` or
    /// space in a filename survives the round trip.
    static func appendingPath(_ path: String, to url: URL) -> URL? {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        let encoded = path.addingPercentEncoding(withAllowedCharacters: allowed) ?? path
        let existing = components.percentEncodedQuery.map { $0 + "&" } ?? ""
        components.percentEncodedQuery = existing + "path=" + encoded
        return components.url
    }

    private final class ProgressRelay: NSObject, URLSessionTaskDelegate, Sendable {
        let onProgress: @Sendable (Int64, Int64) -> Void

        init(onProgress: @escaping @Sendable (Int64, Int64) -> Void) {
            self.onProgress = onProgress
        }

        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            didSendBodyData bytesSent: Int64,
            totalBytesSent: Int64,
            totalBytesExpectedToSend: Int64
        ) {
            onProgress(totalBytesSent, totalBytesExpectedToSend)
        }
    }
}
