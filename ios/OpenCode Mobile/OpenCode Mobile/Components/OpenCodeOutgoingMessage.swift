import SwiftUI

private let outgoingMessagePaddingHorizontal: CGFloat = 12
private let outgoingMessagePaddingVertical: CGFloat = 8

private let outgoingMessageMaxWidthFraction: CGFloat = 0.8

private struct OutgoingMessageAvailableWidthKey: PreferenceKey {
    static var defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

struct OpenCodeOutgoingMessage: View {
    let text: String
    let renderedMarkdown: NativeMarkdownDocument?
    let metadataLabel: String

    @State private var availableMaxWidth: CGFloat = 0

    init(
        text: String,
        renderedMarkdown: NativeMarkdownDocument? = nil,
        metadataLabel: String
    ) {
        self.text = text
        self.renderedMarkdown = renderedMarkdown
        self.metadataLabel = metadataLabel
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            textPill
            metadataFooter
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .background {
            GeometryReader { geometry in
                Color.clear.preference(
                    key: OutgoingMessageAvailableWidthKey.self,
                    value: geometry.size.width * outgoingMessageMaxWidthFraction
                )
            }
        }
        .onPreferenceChange(OutgoingMessageAvailableWidthKey.self) { width in
            guard width > 0, availableMaxWidth != width else { return }
            availableMaxWidth = width
        }
    }

    private var textPill: some View {
        pillContent
            .padding(.horizontal, outgoingMessagePaddingHorizontal)
            .padding(.vertical, outgoingMessagePaddingVertical)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private var metadataFooter: some View {
        constrainedToMaxWidth(usesContentInset: false) {
            Text(metadataLabel)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .lineLimit(1)
                .truncationMode(.tail)
                .multilineTextAlignment(.trailing)
        }
    }

    @ViewBuilder
    private var pillContent: some View {
        if let renderedMarkdown {
            constrainedToMaxWidth(usesContentInset: true) {
                OpenCodeRenderedMarkdown(
                    document: renderedMarkdown,
                    variant: .outgoingMessage
                )
            }
        } else {
            constrainedToMaxWidth(usesContentInset: true) {
                Text(text)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .multilineTextAlignment(.trailing)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder
    private func constrainedToMaxWidth<Content: View>(
        usesContentInset: Bool,
        @ViewBuilder content: () -> Content
    ) -> some View {
        if availableMaxWidth > 0 {
            content()
                .frame(
                    maxWidth: usesContentInset ? contentMaxWidth : availableMaxWidth,
                    alignment: .trailing
                )
        } else {
            content()
        }
    }

    private var contentMaxWidth: CGFloat {
        max(availableMaxWidth - (outgoingMessagePaddingHorizontal * 2), 0)
    }
}

#Preview("Short") {
    OpenCodeOutgoingMessage(
        text: "Based on the standard 2026 pricing for DHL in Germany, confirm the package class.",
        metadataLabel: "Build · DeepSeek V4 Flash · 9:05 PM"
    )
        .padding()
        .openCodeTheme()
}

#Preview("Long") {
    OpenCodeOutgoingMessage(
        text: outgoingMessageLongPreviewText,
        metadataLabel: "Build · DeepSeek V4 Flash · 9:05 PM"
    )
        .padding()
        .openCodeTheme()
        .preferredColorScheme(.dark)
}

private let outgoingMessageLongPreviewText = """
Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options. Include the likely dimensions, weight limits, and the tradeoffs a customer should consider before choosing it. Keep the answer practical and note where pricing depends on the booking channel or destination. Then summarize the recommendation in one concise sentence for a mobile chat user.
"""
