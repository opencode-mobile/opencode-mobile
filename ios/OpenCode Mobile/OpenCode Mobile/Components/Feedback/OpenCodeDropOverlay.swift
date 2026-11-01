import SwiftUI

/// Whether the items being dragged can be dropped.
enum OpenCodeDropOverlayVariant {
    /// At least one item will be attached.
    case accept
    /// Nothing in the drag can be attached; the platform drop indicator shows no-drop.
    case reject
}

/// Drag-over state for a drop target that covers a whole screen: the content dims and a centred
/// dashed card says what a drop would do. `previews` are the kinds of the first items that will be
/// accepted (at most four are shown); a `.reject` card shows none.
struct OpenCodeDropOverlay: View {
    let variant: OpenCodeDropOverlayVariant
    let title: OpenCodeText
    let subtitle: OpenCodeText
    var previews: [OpenCodeAttachmentVariant] = []

    private static let scrimAlpha: Double = 0.32
    private static let maxPreviews = 4
    private static let cornerRadius: CGFloat = 22

    private var accent: Color {
        variant == .accept ? OpenCodeColors.interactive : OpenCodeColors.error
    }

    var body: some View {
        ZStack {
            Color.black.opacity(Self.scrimAlpha)
                .ignoresSafeArea()
            VStack(spacing: 10) {
                OpenCodeIconImage(icon: variant == .accept ? .arrowDownward : .close, size: 28)
                    .foregroundStyle(accent)
                if variant == .accept, !previews.isEmpty {
                    HStack(spacing: 6) {
                        ForEach(Array(previews.prefix(Self.maxPreviews).enumerated()), id: \.offset) { _, preview in
                            previewTile(preview)
                        }
                    }
                }
                Text(openCode: title)
                    .font(OpenCodeTypography.heading)
                    .foregroundStyle(variant == .accept ? OpenCodeColors.textStrong : accent)
                    .multilineTextAlignment(.center)
                Text(openCode: subtitle)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 28)
            .frame(maxWidth: 420)
            .background(
                RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                    .fill(OpenCodeColors.surfaceRaisedBase)
            )
            .overlay(
                RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
                    .strokeBorder(accent, style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
            )
            .padding(.horizontal, 32)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(title.resolved). \(subtitle.resolved)")
        }
        .accessibilityIdentifier("session-chat-drop-overlay")
    }

    private func previewTile(_ preview: OpenCodeAttachmentVariant) -> some View {
        Text(preview == .image ? "IMG" : preview.label)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textWeak)
            .frame(width: 44, height: 44)
            .background(
                RoundedRectangle(cornerRadius: OpenCodeRadius.small, style: .continuous)
                    .fill(OpenCodeColors.backgroundBase)
            )
            .overlay(
                RoundedRectangle(cornerRadius: OpenCodeRadius.small, style: .continuous)
                    .stroke(OpenCodeColors.borderWeakBase, lineWidth: 1)
            )
    }
}

#Preview("Drop overlay — accept") {
    OpenCodeDropOverlay(
        variant: .accept,
        title: "Drop to attach 2 files",
        subtitle: "1 will be skipped · Folders can't be attached.",
        previews: [.image, .pdf]
    )
    .frame(height: 480)
    .openCodeTheme()
}

#Preview("Drop overlay — reject") {
    OpenCodeDropOverlay(
        variant: .reject,
        title: "Can't attach this",
        subtitle: "Folders can't be attached."
    )
    .frame(height: 480)
    .openCodeTheme()
}
