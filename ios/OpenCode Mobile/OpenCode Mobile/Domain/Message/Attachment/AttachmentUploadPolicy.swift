import Foundation

/// Where a picked file travels (#644).
nonisolated enum AttachmentDestination: Equatable, Sendable {
    /// A `data:` URL in the prompt body, with today's limits and errors.
    case inline
    /// Streamed to the server; the prompt names the resulting path.
    case upload
}

nonisolated enum AttachmentUploadPolicy {
    /// Inline when the model reads the type natively and the file fits the server's inline cap;
    /// otherwise upload when the server supports it; otherwise the inline path, which keeps its
    /// existing errors (including the 20 MB one).
    static func destination(
        mime: String,
        byteCount: Int64,
        model: ModelSelectionOption?,
        uploadAvailable: Bool
    ) -> AttachmentDestination {
        let native = model?.readsNatively(mime: mime) ?? false
        if native, byteCount <= AttachmentLimits.maxDecodedBytes { return .inline }
        return uploadAvailable ? .upload : .inline
    }
}

nonisolated enum AttachmentDuplicate {
    /// A file is a duplicate when a draft attachment, pending or uploaded, has its name and size.
    static func isDuplicate(filename: String, byteCount: Int64, in draft: [Attachment]) -> Bool {
        draft.contains { $0.filename == filename && $0.byteCount == byteCount }
    }

    static let message = "This file has already been uploaded"
}

/// What a finished upload request means (#644 failure table).
nonisolated enum AttachmentUploadFailure: Equatable, Sendable {
    /// The route is not what the contract promised: clear the flag, send the file the old way.
    case contractMiss
    /// Timeout, lost connection, server or auth failure: remove the chip, show "Upload failed".
    case transport

    static let message = "Upload failed"

    /// `nil` when the response is a success carrying a `data.path`.
    static func classify(statusCode: Int, hasPath: Bool) -> AttachmentUploadFailure? {
        switch statusCode {
        case 200 ..< 300:
            return hasPath ? nil : .contractMiss
        case 401, 403, 408, 429, 500...:
            return .transport
        default:
            // 404, 405, 400, 415 and any other client error: the route changed.
            return .contractMiss
        }
    }
}
