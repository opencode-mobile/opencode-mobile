import SwiftUI
import UIKit

struct AttachmentGalleryView: View {
    private static let sampleImage = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 64)).image { context in
        UIColor(red: 0.35, green: 0.45, blue: 0.55, alpha: 1).setFill()
        context.fill(CGRect(origin: .zero, size: CGSize(width: 64, height: 64)))
    }

    @State private var attachments = [
        OpenCodeAttachmentPresentation(
            id: "image",
            filename: "session-screenshot.png",
            variant: .image,
            image: Self.sampleImage
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
            image: Self.sampleImage
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
