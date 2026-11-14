import Foundation

/// Attachment rules stay with OpenCode Mobile's composer; the model types live in OpenCodeKit.
extension ModelSelectionOption {
    func acceptsAttachmentKind(_ kind: AttachmentKind) -> Bool {
        switch kind {
        case .image: supportsImageInput
        case .pdf: supportsPdfInput
        case .text, .file: true
        }
    }

    /// An uploaded file never reaches the model's input, so any model takes it.
    func accepts(_ attachment: Attachment) -> Bool {
        attachment.upload != .inline || acceptsAttachmentKind(attachment.kind)
    }
}
