import SwiftUI

private let outgoingMessagePaddingHorizontal: CGFloat = 12
private let outgoingMessagePaddingVertical: CGFloat = 8

private let outgoingMessageMaxWidthFraction: CGFloat = 0.8

/// A text cut to the part the timeline shows; the bubble ends with a row that opens the rest.
struct OpenCodeOutgoingMessageLimit {
    let shownCharacters: Int
    let totalCharacters: Int
    /// Opens the whole text. Nil: the row is not drawn.
    let onShowFull: (() -> Void)?
}

enum OpenCodeOutgoingMessageContent: Identifiable {
    /// `limit`: the text is the shown part of a longer message.
    case text(id: String, text: String, limit: OpenCodeOutgoingMessageLimit? = nil)
    case attachment(OpenCodeAttachmentPresentation)
    /// Attached images as one gallery; `onOpen` receives the tapped index.
    case images(id: String, items: [OpenCodeImageGalleryItem], onOpen: ((Int) -> Void)?)

    var id: String {
        switch self {
        case .text(let id, _, _): "text-\(id)"
        case .attachment(let attachment): "attachment-\(attachment.id)"
        case .images(let id, _, _): "images-\(id)"
        }
    }
}

/// A prompt steered into a running turn. Pending: dimmed with a dashed outline and
/// a caption until the next step delivers it. Either way the first of a run of
/// steers carries the "Steering" label.
enum OpenCodeOutgoingMessageSteering: Equatable {
    case pending(showsLabel: Bool)
    case delivered(showsLabel: Bool)

    static let label = "↱ Steering"
    static let pendingCaption = "Pending · sends at the next step"

    var isPending: Bool {
        if case .pending = self { return true }
        return false
    }

    var showsLabel: Bool {
        switch self {
        case .pending(let showsLabel), .delivered(let showsLabel): showsLabel
        }
    }
}

/// A label over the bubble that says what kind of prompt it is.
enum OpenCodeOutgoingMessageHeader: Equatable {
    /// A `/goal` kickoff the server sent as the user's prompt: the goal mark and `label`.
    case goal(label: String)
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
    /// Agent and model; read by VoiceOver with the sent time, no longer drawn.
    let metadataLabel: String
    /// Revealed by dragging the message left. Nil: no reveal.
    let sentAt: Date?
    let steering: OpenCodeOutgoingMessageSteering?
    let header: OpenCodeOutgoingMessageHeader?
    let onTap: (() -> Void)?
    let onLongPress: (() -> Void)?

    init(
        text: String,
        renderedMarkdown: NativeMarkdownDocument? = nil,
        attachments: [OpenCodeAttachmentPresentation] = [],
        metadataLabel: String,
        sentAt: Date? = nil,
        steering: OpenCodeOutgoingMessageSteering? = nil,
        header: OpenCodeOutgoingMessageHeader? = nil,
        onTap: (() -> Void)? = nil,
        onLongPress: (() -> Void)? = nil
    ) {
        self.header = header
        self.content = attachments.map(OpenCodeOutgoingMessageContent.attachment) + [
            .text(id: "message", text: text),
        ]
        self.renderedMarkdown = renderedMarkdown
        self.metadataLabel = metadataLabel
        self.sentAt = sentAt
        self.steering = steering
        self.onTap = onTap
        self.onLongPress = onLongPress
    }

    init(
        content: [OpenCodeOutgoingMessageContent],
        renderedMarkdown: NativeMarkdownDocument? = nil,
        metadataLabel: String,
        sentAt: Date? = nil,
        steering: OpenCodeOutgoingMessageSteering? = nil,
        header: OpenCodeOutgoingMessageHeader? = nil,
        onTap: (() -> Void)? = nil,
        onLongPress: (() -> Void)? = nil
    ) {
        self.header = header
        self.content = content
        self.renderedMarkdown = renderedMarkdown
        self.metadataLabel = metadataLabel
        self.sentAt = sentAt
        self.steering = steering
        self.onTap = onTap
        self.onLongPress = onLongPress
    }

    var body: some View {
        OutgoingMessageTrailingMaxWidthLayout(maxWidthFraction: outgoingMessageMaxWidthFraction) {
            VStack(alignment: .trailing, spacing: 4) {
                if case let .goal(label) = header {
                    HStack(spacing: 4) {
                        OpenCodeIconImage(icon: .radioButtonChecked, size: 12)
                        Text(label)
                    }
                    .openCodeFont(.caption)
                    .fontWeight(.semibold)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                } else if steering?.showsLabel == true {
                    Text(OpenCodeOutgoingMessageSteering.label)
                        .openCodeFont(.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .accessibilityAddTraits(.isHeader)
                }
                ForEach(content) { item in
                    switch item {
                    case .text(_, let text, let limit):
                        if let limit, let onShowFull = limit.onShowFull {
                            interactiveTextPill(text: text, limit: limit, onShowFull: onShowFull)
                        } else if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            interactiveTextPill(text: text)
                        }
                    case .attachment(let attachment):
                        OpenCodeMessageAttachment(attachment: attachment)
                            .opacity(isPending ? pendingOpacity : 1)
                    case .images(_, let items, let onOpen):
                        OpenCodeImageGallery(items: items, onOpen: onOpen)
                            .opacity(isPending ? pendingOpacity : 1)
                    }
                }
                if isPending {
                    Text(OpenCodeOutgoingMessageSteering.pendingCaption)
                        .openCodeFont(.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .multilineTextAlignment(.trailing)
                }
            }
            .openCodeTimeReveal(time)
        }
    }

    private var time: OpenCodeMessageTime? {
        sentAt.map { OpenCodeMessageTime(sentAt: $0) }
    }

    /// The agent and model (`metadataLabel`) and the time the drag reveal hides from VoiceOver,
    /// plus the day when the message is not from today. `time` is already in the reader's locale.
    static func accessibilityValue(metadataLabel: String, time: OpenCodeMessageTime?) -> String {
        let details = [metadataLabel.isEmpty ? nil : metadataLabel, time?.time]
            .compactMap { $0 }
            .joined(separator: " · ")
        return [time?.day, details.isEmpty ? nil : details].compactMap { $0 }.joined(separator: ", ")
    }

    private var accessibilityValue: String {
        Self.accessibilityValue(metadataLabel: metadataLabel, time: time)
    }

    private var isPending: Bool { steering?.isPending == true }

    private var pendingOpacity: Double { 0.6 }

    private func textPill(text: String, limit: OpenCodeOutgoingMessageLimit? = nil, onShowFull: (() -> Void)? = nil) -> some View {
        // A cut bubble is as wide as the wider of its text and the row, like an uncut bubble
        // hugs its text; the row alone would stretch it to the full width. Rendered markdown
        // takes the full width as in an uncut bubble, so it keeps the plain stack.
        let layout = limit == nil || rendersMarkdown
            ? AnyLayout(VStackLayout(alignment: .trailing, spacing: 8))
            : AnyLayout(OutgoingLimitedTextLayout(spacing: 8))
        return layout {
            if !text.isEmpty {
                pillContent(text: text)
            }
            if let limit, let onShowFull {
                OpenCodeTruncationRow(
                    variant: .fullMessage(shownCharacters: limit.shownCharacters, totalCharacters: limit.totalCharacters),
                    action: onShowFull
                )
            }
        }
            .padding(.horizontal, outgoingMessagePaddingHorizontal)
            .padding(.vertical, outgoingMessagePaddingVertical)
            .background(
                OpenCodeColors.surfaceInteractiveWeak,
                in: RoundedRectangle(cornerRadius: 8)
            )
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .opacity(isPending ? pendingOpacity : 1)
            .overlay {
                if isPending {
                    RoundedRectangle(cornerRadius: 8)
                        .strokeBorder(
                            OpenCodeColors.textWeaker,
                            style: StrokeStyle(lineWidth: 1, dash: [4, 3])
                        )
                }
            }
    }

    /// A cut text: the bubble stays one VoiceOver element with the message actions, and the
    /// row is also a named action on it.
    private func interactiveTextPill(
        text: String,
        limit: OpenCodeOutgoingMessageLimit,
        onShowFull: @escaping () -> Void
    ) -> some View {
        let open = onTap ?? onLongPress
        let variant = OpenCodeTruncationRowVariant.fullMessage(
            shownCharacters: limit.shownCharacters,
            totalCharacters: limit.totalCharacters
        )
        return textPill(text: text, limit: limit, onShowFull: onShowFull)
            .contentShape(RoundedRectangle(cornerRadius: 8))
            .gesture(TapGesture().onEnded { onTap?() }, including: onTap == nil ? .subviews : .all)
            .gesture(LongPressGesture().onEnded { _ in onLongPress?() }, including: onLongPress == nil ? .subviews : .all)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(Text("component.outgoing_message.label"))
            .accessibilityValue([variant.caption, accessibilityValue].filter { !$0.isEmpty }.joined(separator: ", "))
            .accessibilityHint(open == nil ? Text(verbatim: "") : Text("component.outgoing_message.shows_actions_hint"))
            // Combined, the row's button would take the bubble's activation; keep it on the bubble.
            .accessibilityAction { open?() }
            .accessibilityAction(named: Text(variant.title), onShowFull)
            .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: OpenCodeRadius.small), enabled: open != nil, activate: open)
            .openCodeSecondaryClick(onLongPress)
    }

    @ViewBuilder
    private func interactiveTextPill(text: String) -> some View {
        switch (onTap, onLongPress) {
        case (let onTap?, let onLongPress?):
            textPill(text: text)
                .onTapGesture(perform: onTap)
                .onLongPressGesture(perform: onLongPress)
                .accessibilityLabel(Text("component.outgoing_message.label"))
                .accessibilityValue(accessibilityValue)
                .accessibilityHint(Text("component.outgoing_message.shows_actions_hint"))
                .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: OpenCodeRadius.small), activate: onTap)
                .openCodeSecondaryClick(onLongPress)
        case (nil, let onLongPress?):
            textPill(text: text)
                .onLongPressGesture(perform: onLongPress)
                .accessibilityLabel(Text("component.outgoing_message.label"))
                .accessibilityValue(accessibilityValue)
                .accessibilityHint(Text("component.outgoing_message.long_press_hint"))
                .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: OpenCodeRadius.small), activate: onLongPress)
                .openCodeSecondaryClick(onLongPress)
        case (let onTap?, nil):
            textPill(text: text)
                .onTapGesture(perform: onTap)
                .accessibilityLabel(Text("component.outgoing_message.label"))
                .accessibilityValue(accessibilityValue)
                .accessibilityHint(Text("component.outgoing_message.shows_actions_hint"))
                .openCodeKeyboardFocusable(.roundedRectangle(cornerRadius: OpenCodeRadius.small), activate: onTap)
        case (nil, nil):
            textPill(text: text)
                .accessibilityValue(accessibilityValue)
        }
    }

    /// The text renders as markdown: one text in the bubble and a document for it. A cut text's
    /// document is parsed from its shown part, so it renders like an uncut one (#1043).
    private var rendersMarkdown: Bool {
        renderedMarkdown != nil && content.lazy.filter({
            if case .text = $0 { return true }
            return false
        }).count == 1
    }

    @ViewBuilder
    private func pillContent(text: String) -> some View {
        if rendersMarkdown, let renderedMarkdown {
            OpenCodeRenderedMarkdown(
                document: renderedMarkdown,
                variant: .outgoingMessage
            )
            .foregroundStyle(OpenCodeColors.textStrong)
        } else {
            Text(attributedCommandDisplayText(text))
                .openCodeFont(.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Stacks a cut text and its truncation row at one width: the wider of the text (wrapped to the
/// offered width) and the row's natural width, never more than offered.
private struct OutgoingLimitedTextLayout: Layout {
    let spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = sharedWidth(proposal: proposal, subviews: subviews)
        let heights = subviews.map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }
        let gaps = spacing * CGFloat(max(0, subviews.count - 1))
        return CGSize(width: width, height: heights.reduce(0, +) + gaps)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        // Trailing, as the bubble's text sits when the row is the wider of the two.
        var y = bounds.minY
        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            subview.place(
                at: CGPoint(x: bounds.maxX, y: y),
                anchor: .topTrailing,
                proposal: ProposedViewSize(width: bounds.width, height: size.height)
            )
            y += size.height + spacing
        }
    }

    private func sharedWidth(proposal: ProposedViewSize, subviews: Subviews) -> CGFloat {
        let widths = subviews.map { subview in
            let natural = subview.sizeThatFits(.unspecified).width
            guard let offered = proposal.width else { return natural }
            return min(natural, subview.sizeThatFits(ProposedViewSize(width: offered, height: nil)).width)
        }
        let width = widths.max() ?? 0
        return proposal.width.map { min(width, $0) } ?? width
    }
}

/// Every `[/name]` token (a collapsed command or skill span, anywhere in the text) takes the
/// syntax-type colour; the rest keeps the body colour.
private func attributedCommandDisplayText(_ text: String) -> AttributedString {
    var attributed = AttributedString(text)
    attributed.foregroundColor = OpenCodeColors.textStrong
    for range in CommandInvocationCollapser.tokenRanges(in: text) {
        guard let lower = AttributedString.Index(String.Index(utf16Offset: range.lowerBound, in: text), within: attributed),
              let upper = AttributedString.Index(String.Index(utf16Offset: range.upperBound, in: text), within: attributed)
        else { continue }
        attributed[lower ..< upper].foregroundColor = OpenCodeColors.syntaxType
    }
    return attributed
}

#Preview("Short") {
    OpenCodeOutgoingMessage(
        text: "Based on the standard 2026 pricing for DHL in Germany, confirm the package class.",
        metadataLabel: "Build · DeepSeek V4 Flash",
        sentAt: Date()
    )
        .padding()
        .openCodeTheme()
}

#Preview("Long") {
    OpenCodeOutgoingMessage(
        text: outgoingMessageLongPreviewText,
        metadataLabel: "Build · DeepSeek V4 Flash"
    )
        .padding()
        .openCodeTheme()
        .preferredColorScheme(.dark)
}

#Preview("Steering") {
    VStack(spacing: 12) {
        OpenCodeOutgoingMessage(
            text: "Use the staging bucket instead.",
            metadataLabel: "Build · DeepSeek V4 Flash",
            steering: .delivered(showsLabel: true)
        )
        OpenCodeOutgoingMessage(
            text: "And skip the migration step.",
            metadataLabel: "Build · DeepSeek V4 Flash",
            steering: .pending(showsLabel: false)
        )
    }
    .padding()
    .openCodeTheme()
}

private let outgoingMessageLongPreviewText = """
Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options. Include the likely dimensions, weight limits, and the tradeoffs a customer should consider before choosing it. Keep the answer practical and note where pricing depends on the booking channel or destination. Then summarize the recommendation in one concise sentence for a mobile chat user.
"""
