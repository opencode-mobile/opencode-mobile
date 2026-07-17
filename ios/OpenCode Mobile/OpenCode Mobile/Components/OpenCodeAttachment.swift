import SwiftUI
import UIKit

enum OpenCodeAttachmentVariant: String {
    case image
    case pdf
    case text

    var label: String { rawValue.uppercased() }
}

struct OpenCodeAttachmentPresentation: Identifiable {
    let id: String
    let filename: String
    let variant: OpenCodeAttachmentVariant
    let image: UIImage?

    init(id: String, filename: String, variant: OpenCodeAttachmentVariant, image: UIImage? = nil) {
        self.id = id
        self.filename = filename
        self.variant = variant
        self.image = image
    }
}

struct OpenCodeAttachmentChip: View {
    let attachment: OpenCodeAttachmentPresentation
    let onRemove: (String) -> Void

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                attachmentPreview(size: 28)
                Text(attachment.filename)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                Text(attachment.variant.label)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(attachment.filename), \(attachment.variant.label)")
            Spacer(minLength: 4)
            Button(action: { onRemove(attachment.id) }) {
                OpenCodeIconImage(icon: .close)
                    .frame(width: 20, height: 20)
                    .foregroundStyle(OpenCodeColors.iconStrongBase)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove \(attachment.filename)")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1))
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private func attachmentPreview(size: CGFloat) -> some View {
        if let image = attachment.image, attachment.variant == .image {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: 4))
        } else {
            OpenCodeIconImage(icon: .description)
                .frame(width: size, height: size)
                .foregroundStyle(OpenCodeColors.iconBase)
        }
    }
}

struct OpenCodeMessageAttachment: View {
    let attachment: OpenCodeAttachmentPresentation

    var body: some View {
        HStack(spacing: 10) {
            if let image = attachment.image, attachment.variant == .image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 52, height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
            } else {
                OpenCodeIconImage(icon: .description)
                    .frame(width: 32, height: 32)
                    .foregroundStyle(OpenCodeColors.iconBase)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(attachment.filename)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                Text(attachment.variant.label)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
            }
        }
        .padding(8)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(attachment.filename), \(attachment.variant.label)")
    }
}

#Preview("Attachment components") {
    VStack(alignment: .leading, spacing: 12) {
        OpenCodeAttachmentChip(
            attachment: .init(id: "text", filename: "implementation-notes-with-a-long-name.md", variant: .text),
            onRemove: { _ in }
        )
        OpenCodeMessageAttachment(
            attachment: .init(id: "pdf", filename: "product-brief.pdf", variant: .pdf)
        )
    }
    .padding()
    .openCodeTheme()
}
