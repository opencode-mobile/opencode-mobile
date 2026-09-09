import SwiftUI

private let outgoingMessagePaddingHorizontal: CGFloat = 12
private let outgoingMessagePaddingVertical: CGFloat = 8

private let outgoingMessageMaxWidthFraction: CGFloat = 0.8

enum OpenCodeOutgoingMessageContent: Identifiable {
    case text(id: String, text: String)
    case attachment(OpenCodeAttachmentPresentation)

    var id: String {
        switch self {
        case .text(let id, _): "text-\(id)"
        case .attachment(let attachment): "attachment-\(attachment.id)"
        }
    }
}

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
    let content: [OpenCodeOutgoingMessageContent]
    let renderedMarkdown: NativeMarkdownDocument?
    let metadataLabel: String
    let waitingCaption: String?
    let waitingDelivery: FollowUpDelivery?
    let onTap: (() -> Void)?
    let onLongPress: (() -> Void)?

    init(
        text: String,
        renderedMarkdown: NativeMarkdownDocument? = nil,
        attachments: [OpenCodeAttachmentPresentation] = [],
        metadataLabel: String,
        waitingCaption: String? = nil,
        waitingDelivery: FollowUpDelivery? = nil,
        onTap: (() -> Void)? = nil,
        onLongPress: (() -> Void)? = nil
    ) {
        self.content = attachments.map(OpenCodeOutgoingMessageContent.attachment) + [
            .text(id: "message", text: text),
        ]
        self.renderedMarkdown = renderedMarkdown
        self.metadataLabel = metadataLabel
        self.waitingCaption = waitingCaption
        self.waitingDelivery = waitingDelivery
        self.onTap = onTap
        self.onLongPress = onLongPress
    }

    init(
        content: [OpenCodeOutgoingMessageContent],
        renderedMarkdown: NativeMarkdownDocument? = nil,
        metadataLabel: String,
        waitingCaption: String? = nil,
        waitingDelivery: FollowUpDelivery? = nil,
        onTap: (() -> Void)? = nil,
        onLongPress: (() -> Void)? = nil
    ) {
        self.content = content
        self.renderedMarkdown = renderedMarkdown
        self.metadataLabel = metadataLabel
        self.waitingCaption = waitingCaption
        self.waitingDelivery = waitingDelivery
        self.onTap = onTap
        self.onLongPress = onLongPress
    }

    var body: some View {
        OutgoingMessageTrailingMaxWidthLayout(maxWidthFraction: outgoingMessageMaxWidthFraction) {
            VStack(alignment: .trailing, spacing: 4) {
                ForEach(content) { item in
                    switch item {
                    case .text(_, let text):
                        if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            interactiveTextPill(text: text)
                        }
                    case .attachment(let attachment):
                        OpenCodeMessageAttachment(attachment: attachment)
                    }
                }
                metadataFooter
                if let waitingCaption {
                    HStack(spacing: 5) {
                        OpenCodeIconImage(
                            icon: waitingDelivery == .queue ? .arrowUpward : .arrowWarmUp,
                            size: 13
                        )
                        .foregroundStyle(OpenCodeColors.textWeak)
                        Text(waitingCaption)
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .lineLimit(1)
                    }
                }
            }
        }
    }

    private func textPill(text: String) -> some View {
        pillContent(text: text)
            .padding(.horizontal, outgoingMessagePaddingHorizontal)
            .padding(.vertical, outgoingMessagePaddingVertical)
            .background(OpenCodeColors.surfaceInteractiveWeak, in: RoundedRectangle(cornerRadius: 8))
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private func interactiveTextPill(text: String) -> some View {
        if let onLongPress {
            textPill(text: text)
                .onLongPressGesture(perform: onLongPress)
                .accessibilityLabel("Outgoing message")
                .accessibilityHint("Long press to show message actions")
        } else if let onTap {
            textPill(text: text)
                .onTapGesture(perform: onTap)
                .accessibilityLabel("Outgoing message")
                .accessibilityHint("Shows message actions")
        } else {
            textPill(text: text)
        }
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
    private func pillContent(text: String) -> some View {
        if let renderedMarkdown, content.lazy.filter({
            if case .text = $0 { return true }
            return false
        }).count == 1 {
            OpenCodeRenderedMarkdown(
                document: renderedMarkdown,
                variant: .outgoingMessage
            )
        } else {
            Text(attributedCommandDisplayText(text))
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private func attributedCommandDisplayText(_ text: String) -> AttributedString {
    var attributed = AttributedString(text)
    guard text.hasPrefix("[/"), let end = text.firstIndex(of: "]") else {
        attributed.foregroundColor = OpenCodeColors.textStrong
        return attributed
    }
    let tokenEnd = text.distance(from: text.startIndex, to: end) + 1
    let tokenRange = attributed.startIndex ..< attributed.index(attributed.startIndex, offsetByCharacters: tokenEnd)
    attributed[tokenRange].foregroundColor = OpenCodeColors.syntaxType
    let remainderStart = attributed.index(attributed.startIndex, offsetByCharacters: tokenEnd)
    if remainderStart < attributed.endIndex {
        attributed[remainderStart ..< attributed.endIndex].foregroundColor = OpenCodeColors.textStrong
    }
    return attributed
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
