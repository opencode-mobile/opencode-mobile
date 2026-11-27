import Foundation

/// Why a picked, pasted or dropped attachment was not added. Carried from where it is rejected to
/// the screen, which shows `text`; `logMessage` is English for diagnostics only. Same cases and
/// keys (`attachment.error.*`) as Android's `AttachmentRejection`.
nonisolated enum AttachmentRejection: Equatable, Sendable {
    /// A folder; only files attach.
    case folder
    /// Same name and size as a file already in the draft.
    case duplicate
    /// Over the inline cap while the server takes no uploads.
    case tooLarge(limitBytes: Int64 = AttachmentLimits.maxDecodedBytes)
    /// Not an image, PDF or text file, while the server takes no uploads.
    case unsupportedType
    /// The selected model reads no images.
    case modelWithoutImages
    /// The selected model reads no PDFs.
    case modelWithoutPDF
    /// The selected model reads some of several attachments but not all.
    case modelWithoutSome
    /// The content could not be read from its provider.
    case unreadable
    /// A `data:` URL that does not decode to its declared type.
    case invalidData
    /// A streamed upload failed.
    case uploadFailed

    var logMessage: String {
        switch self {
        case .folder: "folder"
        case .duplicate: "duplicate"
        case let .tooLarge(limitBytes): "too large limit=\(limitBytes)"
        case .unsupportedType: "unsupported type"
        case .modelWithoutImages: "model reads no images"
        case .modelWithoutPDF: "model reads no pdf"
        case .modelWithoutSome: "model reads some kinds only"
        case .unreadable: "unreadable"
        case .invalidData: "invalid data"
        case .uploadFailed: "upload failed"
        }
    }

    /// What the user reads.
    var text: LocalizedStringResource {
        switch self {
        case .folder:
            LocalizedStringResource("attachment.error.folder", defaultValue: "Folders can't be attached.")
        case .duplicate:
            LocalizedStringResource("attachment.error.duplicate", defaultValue: "This file has already been uploaded")
        case let .tooLarge(limitBytes):
            LocalizedStringResource(
                "attachment.error.too_large",
                defaultValue: "This file is larger than \(Int(limitBytes / (1024 * 1024))) MB."
            )
        case .unsupportedType:
            LocalizedStringResource(
                "attachment.error.unsupported_type",
                defaultValue: "Only images, PDFs, or text files can be attached here."
            )
        case .modelWithoutImages:
            LocalizedStringResource(
                "attachment.error.model_no_images",
                defaultValue: "This model does not support image attachments."
            )
        case .modelWithoutPDF:
            LocalizedStringResource("attachment.error.model_no_pdf", defaultValue: "This model does not support PDF attachments.")
        case .modelWithoutSome:
            LocalizedStringResource(
                "attachment.error.model_some",
                defaultValue: "This model does not support some of the selected attachments."
            )
        case .unreadable:
            LocalizedStringResource("attachment.error.unreadable", defaultValue: "The attachment could not be read.")
        case .invalidData:
            LocalizedStringResource("attachment.error.invalid_data", defaultValue: "The attachment data is invalid.")
        case .uploadFailed:
            LocalizedStringResource("attachment.error.upload_failed", defaultValue: "Upload failed")
        }
    }
}

extension Error {
    /// The rejection a failed attachment load carries; anything unexpected reads as unreadable.
    nonisolated var attachmentRejection: AttachmentRejection {
        (self as? AttachmentLoadError)?.rejection ?? .unreadable
    }
}
