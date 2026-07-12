import SwiftUI

private let outgoingMessagePaddingHorizontal: CGFloat = 12
private let outgoingMessagePaddingVertical: CGFloat = 8

private let outgoingMessageMaxWidthFraction: CGFloat = 0.8

private struct OutgoingMessageTrailingMaxWidthLayout: Layout {
    let maxWidthFraction: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let subview = subviews.first else { return .zero }

        let containerWidth = proposal.width ?? 0
        guard containerWidth > 0 else {
            return subview.sizeThatFits(proposal)
        }

        let maxContentWidth = containerWidth * maxWidthFraction
        let contentSize = subview.sizeThatFits(
            ProposedViewSize(width: maxContentWidth, height: proposal.height)
        )
        return CGSize(width: containerWidth, height: contentSize.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let subview = subviews.first else { return }

        let maxContentWidth = bounds.width * maxWidthFraction
        let contentSize = subview.sizeThatFits(
            ProposedViewSize(width: maxContentWidth, height: proposal.height)
        )
        subview.place(
            at: CGPoint(x: bounds.maxX, y: bounds.minY),
            anchor: .topTrailing,
            proposal: ProposedViewSize(width: contentSize.width, height: contentSize.height)
        )
    }
}

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
        OutgoingMessageTrailingMaxWidthLayout(maxWidthFraction: outgoingMessageMaxWidthFraction) {
            VStack(alignment: .trailing, spacing: 6) {
                textPill
                metadataFooter
            }
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
        Text(metadataLabel)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textWeak)
            .lineLimit(1)
            .truncationMode(.tail)
            .multilineTextAlignment(.trailing)
    }

    @ViewBuilder
    private var pillContent: some View {
        if let renderedMarkdown {
            OpenCodeRenderedMarkdown(
                document: renderedMarkdown,
                variant: .outgoingMessage
            )
        } else {
            Text(text)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
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
