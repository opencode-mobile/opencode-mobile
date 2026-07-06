import SwiftUI
import UIKit

private let outgoingMessageControlIconTouchTarget: CGFloat = 28
private let outgoingMessageActionIconSize: CGFloat = 16

private func outgoingMessageControlIcon(_ icon: OpenCodeIcon, size: CGFloat) -> some View {
    Image(icon.rawValue)
        .renderingMode(.template)
        .resizable()
        .scaledToFit()
        .frame(width: size, height: size)
        .frame(width: outgoingMessageControlIconTouchTarget, height: outgoingMessageControlIconTouchTarget)
        .contentShape(Rectangle())
}

struct OpenCodeOutgoingMessage: View {
    let text: String
    let renderedMarkdown: NativeMarkdownDocument?
    let metadataLabel: String
    let onRestoreClick: () -> Void
    let initialRenderedMarkdownHeight: CGFloat
    let onRenderedMarkdownHeightChange: (CGFloat) -> Void

    init(
        text: String,
        renderedMarkdown: NativeMarkdownDocument? = nil,
        metadataLabel: String,
        initialRenderedMarkdownHeight: CGFloat = 1,
        onRenderedMarkdownHeightChange: @escaping (CGFloat) -> Void = { _ in },
        onRestoreClick: @escaping () -> Void
    ) {
        self.text = text
        self.renderedMarkdown = renderedMarkdown
        self.metadataLabel = metadataLabel
        self.initialRenderedMarkdownHeight = max(1, initialRenderedMarkdownHeight)
        self.onRenderedMarkdownHeightChange = onRenderedMarkdownHeightChange
        self.onRestoreClick = onRestoreClick
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            bubble
            footer
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 56)
        .frame(maxWidth: .infinity, alignment: .trailing)
    }

    private var bubble: some View {
        VStack(alignment: .trailing, spacing: 4) {
            visibleText

            controlRow
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .trailing)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private var footer: some View {
        Text(metadataLabel)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textWeak)
            .lineLimit(1)
            .truncationMode(.tail)
            .frame(maxWidth: .infinity, alignment: .trailing)
    }

    private var controlRow: some View {
        HStack(spacing: 8) {
            Spacer(minLength: 0)

            iconButton(
                icon: .undo,
                accessibilityLabel: "Restore message to composer",
                action: onRestoreClick
            )

            iconButton(
                icon: .contentCopy,
                accessibilityLabel: "Copy message",
                action: { UIPasteboard.general.string = text }
            )
        }
        .foregroundStyle(OpenCodeColors.iconBase)
        .frame(maxWidth: .infinity, alignment: .trailing)
    }

    private func iconButton(
        icon: OpenCodeIcon,
        accessibilityLabel: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            outgoingMessageControlIcon(icon, size: outgoingMessageActionIconSize)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }

    private var visibleText: some View {
        Group {
            if let renderedMarkdown {
                OpenCodeRenderedMarkdown(
                    document: renderedMarkdown,
                    variant: .outgoingMessage,
                    initialHeight: initialRenderedMarkdownHeight,
                    measuresHeight: true,
                    onHeightChange: onRenderedMarkdownHeightChange
                )
            } else {
                Text(text)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

#Preview("Short") {
    OpenCodeOutgoingMessage(
        text: "Based on the standard 2026 pricing for DHL in Germany, confirm the package class.",
        metadataLabel: "Build · DeepSeek V4 Flash · 9:05 PM",
        onRestoreClick: {}
    )
        .padding()
        .openCodeTheme()
}

#Preview("Long") {
    OpenCodeOutgoingMessage(
        text: outgoingMessageLongPreviewText,
        metadataLabel: "Build · DeepSeek V4 Flash · 9:05 PM",
        onRestoreClick: {}
    )
        .padding()
        .openCodeTheme()
        .preferredColorScheme(.dark)
}

private let outgoingMessageLongPreviewText = """
Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options.
Include the likely dimensions, weight limits, and the tradeoffs a customer should consider before choosing it.
Keep the answer practical and note where pricing depends on the booking channel or destination.
Then summarize the recommendation in one concise sentence for a mobile chat user.
"""
