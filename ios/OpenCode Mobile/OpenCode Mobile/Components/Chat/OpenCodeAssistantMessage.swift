import SwiftUI

/// How the assistant text sits in the timeline: the raised bubble, or plain
/// prose for the chip timeline where the tool chips follow the paragraph.
enum OpenCodeAssistantMessagePresentation: Sendable {
    case bubble
    case plain
}

struct OpenCodeAssistantMessage: View {
    let text: String
    var renderedMarkdown: NativeMarkdownDocument? = nil
    var isStreaming: Bool = false
    var presentation: OpenCodeAssistantMessagePresentation = .bubble

    var body: some View {
        Group {
            if let renderedMarkdown {
                OpenCodeRenderedMarkdown(
                    document: renderedMarkdown,
                    variant: .assistantMessage,
                    isStreaming: isStreaming
                )
            } else {
                Text(Self.displayText(text))
                    .font(OpenCodeTypography.body)
                    .lineSpacing(4)
                    .foregroundStyle(OpenCodeColors.textStrong)
            }
        }
        .padding(.horizontal, presentation == .bubble ? Self.paddingHorizontal : Self.plainPaddingHorizontal)
        .padding(.vertical, presentation == .bubble ? Self.paddingVertical : 0)
        .modifier(AssistantMessageWidthModifier())
        .background {
            if presentation == .bubble {
                RoundedRectangle(cornerRadius: Self.cornerRadius)
                    .fill(OpenCodeColors.surfaceRaisedBase)
            }
        }
    }

    static let emptyPlaceholder = "…"

    /// Content wider than this share of the row fills the row, so long
    /// replies line up with full-width cards instead of ending just short.
    static let fillRowThreshold: CGFloat = 0.8

    /// Whether a bubble whose content measures `contentWidth` against
    /// `availableWidth` fills the row. Exactly at the threshold it still hugs.
    static func fillsRow(contentWidth: CGFloat, availableWidth: CGFloat) -> Bool {
        guard availableWidth > 0, availableWidth.isFinite else { return false }
        return contentWidth > availableWidth * fillRowThreshold
    }

    static func displayText(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? emptyPlaceholder : text
    }

    private static let cornerRadius: CGFloat = 8
    private static let paddingHorizontal: CGFloat = 12
    private static let paddingVertical: CGFloat = 8
    private static let plainPaddingHorizontal: CGFloat = 4
}

private struct AssistantMessageWidthModifier: ViewModifier {
    func body(content: Content) -> some View {
        AssistantMessageWidthLayout { content }
    }
}

/// Measures the content once at the available width (capped at the assistant
/// message max width), then reports either its own width or the full row when
/// it passes `OpenCodeAssistantMessage.fillRowThreshold`. The content stays at
/// its own size on the leading edge; only the background drawn around this
/// layout stretches.
private struct AssistantMessageWidthLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let content = subviews.first else { return .zero }
        let available = availableWidth(for: proposal)
        let size = content.sizeThatFits(ProposedViewSize(width: available, height: proposal.height))
        guard let available,
              OpenCodeAssistantMessage.fillsRow(contentWidth: size.width, availableWidth: available)
        else {
            return size
        }
        return CGSize(width: available, height: size.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let content = subviews.first else { return }
        // Same proposal as in sizeThatFits, so the content reuses its measurement.
        content.place(
            at: bounds.origin,
            anchor: .topLeading,
            proposal: ProposedViewSize(width: availableWidth(for: proposal), height: proposal.height)
        )
    }

    private func availableWidth(for proposal: ProposedViewSize) -> CGFloat? {
        guard let width = proposal.width, width.isFinite else {
            return proposal.width == nil ? nil : OpenCodeLayout.assistantMessageMaxWidth
        }
        return min(width, OpenCodeLayout.assistantMessageMaxWidth)
    }
}

#if DEBUG
private enum AssistantMessagePreviewCase: String, CaseIterable, Identifiable {
    case short
    case long
    case empty

    var id: String { rawValue }

    var text: String {
        switch self {
        case .short:
            "The Alleskoenner is the right class for that parcel."
        case .long:
            "Based on the standard 2026 pricing for DHL in Germany, compare the Alleskoenner package to the smaller parcel options and keep the recommendation practical."
        case .empty:
            ""
        }
    }
}

#Preview("Assistant Message") {
    ScrollView {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(AssistantMessagePreviewCase.allCases) { preview in
                OpenCodeAssistantMessage(text: preview.text)
            }
        }
        .padding()
    }
    .openCodeTheme()
}
#endif
