import Foundation

public nonisolated enum AttachmentKind: String, Equatable, Sendable {
    case image
    case pdf
    case text
    /// Anything else, which only travels as a file uploaded to the server.
    case file
}

/// Where an attachment's bytes live: in the prompt body (`inline`), on their way to the server,
/// or already on the server at `serverPath` (streamed upload, #644).
public nonisolated enum AttachmentUploadState: Equatable, Sendable {
    case inline
    case uploading(fraction: Double)
    case uploaded(serverPath: String)

    public var isPending: Bool {
        if case .uploading = self { return true }
        return false
    }

    public var serverPath: String? {
        if case let .uploaded(path) = self { return path }
        return nil
    }
}

public nonisolated struct Attachment: Equatable, Sendable, Identifiable {
    public let id: String
    public let filename: String
    public let mime: String
    public let kind: AttachmentKind
    public let dataURL: String
    public let fileURL: URL?
    public let byteCount: Int64
    public let previewData: Data?
    public var upload: AttachmentUploadState

    public var usesFileBackedBody: Bool { fileURL != nil }
    /// Bytes already on the server: the prompt names the path instead of carrying them.
    public var uploadedServerPath: String? { upload.serverPath }
    public var isServerFile: Bool { uploadedServerPath != nil }
    public var isUploadPending: Bool { upload.isPending }

    public init(
        id: String,
        filename: String,
        mime: String,
        kind: AttachmentKind,
        dataURL: String,
        fileURL: URL? = nil,
        byteCount: Int64 = 0,
        previewData: Data? = nil,
        upload: AttachmentUploadState = .inline
    ) {
        self.id = id
        self.filename = filename
        self.mime = mime
        self.kind = kind
        self.dataURL = dataURL
        self.fileURL = fileURL
        self.byteCount = byteCount
        self.previewData = previewData
        self.upload = upload
    }

    public func withUpload(_ upload: AttachmentUploadState) -> Attachment {
        var copy = self
        copy.upload = upload
        return copy
    }
}

extension Sequence where Element == Attachment {
    /// Attachments whose bytes ride in the prompt body.
    public var inlineAttachments: [Attachment] { filter { !$0.isServerFile } }
    /// Attachments already on the server; the prompt names their paths.
    public var uploadedAttachments: [Attachment] { filter(\.isServerFile) }
}
