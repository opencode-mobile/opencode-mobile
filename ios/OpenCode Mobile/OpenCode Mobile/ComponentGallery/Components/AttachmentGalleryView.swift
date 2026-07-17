import SwiftUI
import UIKit

struct AttachmentGalleryView: View {
    @State private var attachments = [
        OpenCodeAttachmentPresentation(
            id: "image",
            filename: "session-screenshot.png",
            variant: .image,
            image: UIImage(systemName: "photo.fill")
        ),
        OpenCodeAttachmentPresentation(id: "pdf", filename: "product-brief.pdf", variant: .pdf),
        OpenCodeAttachmentPresentation(
            id: "text",
            filename: "implementation-notes-with-a-very-long-filename.md",
            variant: .text
        ),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Pending")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            VStack(spacing: 8) {
                ForEach(attachments) { attachment in
                    OpenCodeAttachmentChip(attachment: attachment) { id in
                        attachments.removeAll { $0.id == id }
                    }
                }
            }

            Text("Sent")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            VStack(spacing: 8) {
                ForEach(Self.sentAttachments) { attachment in
                    OpenCodeMessageAttachment(attachment: attachment)
                }
            }
        }
    }

    private static let sentAttachments = [
        OpenCodeAttachmentPresentation(
            id: "sent-image",
            filename: "session-screenshot.png",
            variant: .image,
            image: UIImage(systemName: "photo.fill")
        ),
        OpenCodeAttachmentPresentation(id: "sent-pdf", filename: "product-brief.pdf", variant: .pdf),
        OpenCodeAttachmentPresentation(id: "sent-text", filename: "notes.md", variant: .text),
    ]
}

#Preview("Attachment gallery") {
    ScrollView {
        AttachmentGalleryView().padding()
    }
    .openCodeTheme()
}
