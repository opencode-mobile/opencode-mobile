import SwiftUI

private let outgoingMessagePaddingHorizontal: CGFloat = 12
private let outgoingMessagePaddingVertical: CGFloat = 8

private let outgoingMessageMaxWidthFraction: CGFloat = 0.8

struct OpenCodeOutgoingMessage: View {
    let text: String
    let renderedMarkdown: NativeMarkdownDocument?
    let metadataLabel: String

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
        GeometryReader { geometry in
            let maxBubbleWidth = geometry.size.width * outgoingMessageMaxWidthFraction

            VStack(alignment: .trailing, spacing: 6) {
                textPill(maxBubbleWidth: maxBubbleWidth)
                metadataFooter(maxBubbleWidth: maxBubbleWidth)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private func textPill(maxBubbleWidth: CGFloat) -> some View {
        pillContent(maxBubbleWidth: maxBubbleWidth)
            .padding(.horizontal, outgoingMessagePaddingHorizontal)
            .padding(.vertical, outgoingMessagePaddingVertical)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .frame(maxWidth: maxBubbleWidth, alignment: .trailing)
    }

    private func metadataFooter(maxBubbleWidth: CGFloat) -> some View {
        Text(metadataLabel)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textWeak)
            .lineLimit(1)
            .truncationMode(.tail)
            .multilineTextAlignment(.trailing)
            .frame(maxWidth: maxBubbleWidth, alignment: .trailing)
    }

    @ViewBuilder
    private func pillContent(maxBubbleWidth: CGFloat) -> some View {
        let contentMaxWidth = max(maxBubbleWidth - (outgoingMessagePaddingHorizontal * 2), 0)

        if let renderedMarkdown {
            OpenCodeRenderedMarkdown(
                document: renderedMarkdown,
                variant: .outgoingMessage
            )
            .frame(maxWidth: contentMaxWidth, alignment: .trailing)
        } else {
            Text(text)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: contentMaxWidth, alignment: .trailing)
        }
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
