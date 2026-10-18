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
    /// Second line under the name; the variant label when nil.
    let detail: String?

    init(
        id: String,
        filename: String,
        variant: OpenCodeAttachmentVariant,
        image: UIImage? = nil,
        detail: String? = nil
    ) {
        self.id = id
        self.filename = filename
        self.variant = variant
        self.image = image
        self.detail = detail
    }

    var detailLabel: String { detail ?? variant.label }
}

struct OpenCodeAttachmentChip: View {
    let attachment: OpenCodeAttachmentPresentation
    /// When set, tapping the preview and name opens the attachment (for example
    /// to mark up a screenshot or preview a log).
    var onOpen: ((String) -> Void)?
    let onRemove: (String) -> Void

    init(
        attachment: OpenCodeAttachmentPresentation,
        onOpen: ((String) -> Void)? = nil,
        onRemove: @escaping (String) -> Void
    ) {
        self.attachment = attachment
        self.onOpen = onOpen
        self.onRemove = onRemove
    }

    var body: some View {
        HStack(spacing: 8) {
            if let onOpen {
                Button(action: { onOpen(attachment.id) }) {
                    chipLabel
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(attachment.filename), \(attachment.detailLabel)")
                .accessibilityAddTraits(.isButton)
            } else {
                chipLabel
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(attachment.filename), \(attachment.detailLabel)")
            }
            Button(action: { onRemove(attachment.id) }) {
                OpenCodeIconImage(icon: .close, size: 20)
                    .foregroundStyle(OpenCodeColors.iconStrongBase)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove \(attachment.filename)")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1))
        .accessibilityElement(children: .contain)
    }

    private var chipLabel: some View {
        HStack(spacing: 8) {
            attachmentPreview(size: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(attachment.filename)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                Text(attachment.detailLabel)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
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
            OpenCodeIconImage(icon: .description, size: size)
                .foregroundStyle(OpenCodeColors.iconBase)
        }
    }
}

/// Dashed chip that adds a missing attachment ("+ Screenshot").
struct OpenCodeAttachmentAddChip: View {
    let title: String
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                OpenCodeIconImage(icon: .add, size: 16)
                    .foregroundStyle(OpenCodeColors.iconBase)
                Text(title)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeak)
                    .lineLimit(1)
            }
            .padding(.horizontal, 10)
            .frame(minHeight: 32)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(OpenCodeColors.borderWeakBase, style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
            )
            .contentShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel("Add \(title.lowercased())")
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
                OpenCodeIconImage(icon: .description, size: 32)
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
        OpenCodeAttachmentChip(
            attachment: .init(id: "logs", filename: "Logs · 15 min", variant: .text, detail: "Tap to preview"),
            onOpen: { _ in },
            onRemove: { _ in }
        )
        OpenCodeAttachmentAddChip(title: "Screenshot", action: {})
        OpenCodeMessageAttachment(
            attachment: .init(id: "pdf", filename: "product-brief.pdf", variant: .pdf)
        )
    }
    .padding()
    .openCodeTheme()
}
