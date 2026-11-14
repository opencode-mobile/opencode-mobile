import Foundation

/// Sends a request's JSON body so that it survives the app leaving the foreground. OpenCode
/// Mobile's `DurableJSONUploader` (background URL session and UIKit background time) adopts it.
public nonisolated protocol DurableJSONUploading: Sendable {
    func upload(
        _ request: URLRequest,
        body: Data,
        directFirst: Bool,
        onProgress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> (Data, URLResponse)

    func upload(
        _ request: URLRequest,
        fileURL: URL,
        directFirst: Bool,
        onProgress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> (Data, URLResponse)
}

extension DurableJSONUploading {
    public func upload(
        _ request: URLRequest,
        body: Data,
        onProgress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> (Data, URLResponse) {
        try await upload(request, body: body, directFirst: false, onProgress: onProgress)
    }

    public func upload(
        _ request: URLRequest,
        fileURL: URL,
        onProgress: (@Sendable (Int64, Int64) -> Void)?
    ) async throws -> (Data, URLResponse) {
        try await upload(request, fileURL: fileURL, directFirst: false, onProgress: onProgress)
    }
}
