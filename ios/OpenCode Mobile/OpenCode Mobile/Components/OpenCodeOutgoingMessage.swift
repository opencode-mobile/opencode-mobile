import SwiftUI
import UIKit

private let outgoingMessageControlIconTouchTarget: CGFloat = 28
private let outgoingMessageExpandCollapseIconSize: CGFloat = 16
private let outgoingMessageActionIconSize: CGFloat = 16
private let outgoingMessageCollapsedRenderedMarkdownHeight: CGFloat = 140

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
    @State private var expanded: Bool
    @State private var fullTextHeight: CGFloat = 0
    @State private var collapsedTextHeight: CGFloat = 0
    @State private var renderedMarkdownHeight: CGFloat = 1

    init(
        text: String,
        renderedMarkdown: NativeMarkdownDocument? = nil,
        metadataLabel: String,
        initiallyExpanded: Bool = false,
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
        _expanded = State(initialValue: initiallyExpanded)
        _renderedMarkdownHeight = State(initialValue: max(1, initialRenderedMarkdownHeight))
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
        .animation(nil, value: expanded)
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .trailing)
        .background {
            measurementText
        }
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private var footer: some View {
        Text(metadataLabel)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(OpenCodeColors.textTertiary)
            .lineLimit(1)
            .truncationMode(.tail)
            .frame(maxWidth: .infinity, alignment: .trailing)
    }

    private var controlRow: some View {
        HStack(spacing: 8) {
            if isExpandable {
                Button(action: toggleExpanded) {
                    outgoingMessageControlIcon(
                        expanded ? .collapseContent : .expandContent,
                        size: outgoingMessageExpandCollapseIconSize
                    )
                }
                .buttonStyle(.plain)
                .accessibilityLabel(expanded ? "Collapse message" : "Expand message")
            } else {
                Spacer(minLength: 0)
            }

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
        .foregroundStyle(OpenCodeColors.iconDefault)
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

    private func toggleExpanded() {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            expanded.toggle()
        }
    }

    private var visibleText: some View {
        Group {
            if let renderedMarkdown {
                OpenCodeRenderedMarkdown(
                    document: renderedMarkdown,
                    variant: .outgoingMessage,
                    initialHeight: initialRenderedMarkdownHeight,
                    onHeightChange: { height in
                        renderedMarkdownHeight = height
                        onRenderedMarkdownHeightChange(min(height, outgoingMessageCollapsedRenderedMarkdownHeight))
                    }
                )
                .frame(height: expanded ? renderedMarkdownHeight : min(renderedMarkdownHeight, outgoingMessageCollapsedRenderedMarkdownHeight))
                .clipped()
            } else {
                Text(text)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textPrimary)
                    .lineLimit(expanded ? nil : 5)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var measurementText: some View {
        Group {
            if usesRenderedMarkdown {
                Color.clear
            } else {
                ZStack {
                    Text(text)
                        .font(OpenCodeTypography.body)
                        .lineLimit(nil)
                        .fixedSize(horizontal: false, vertical: true)
                        .readSize { fullTextHeight = $0.height }

                    Text(text)
                        .font(OpenCodeTypography.body)
                        .lineLimit(5)
                        .fixedSize(horizontal: false, vertical: true)
                        .readSize { collapsedTextHeight = $0.height }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .hidden()
    }

    private var usesRenderedMarkdown: Bool {
        renderedMarkdown != nil
    }

    private var isExpandable: Bool {
        if let renderedMarkdown {
            return renderedMarkdown.isLikelyExpandable && renderedMarkdownHeight > outgoingMessageCollapsedRenderedMarkdownHeight + 1
        }
        return fullTextHeight > collapsedTextHeight + 1
    }
}

private struct SizePreferenceKey: PreferenceKey {
    static var defaultValue: CGSize = .zero

    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        value = nextValue()
    }
}

private extension View {
    func readSize(onChange: @escaping (CGSize) -> Void) -> some View {
        background(
            GeometryReader { geometry in
                Color.clear.preference(key: SizePreferenceKey.self, value: geometry.size)
            }
        )
        .onPreferenceChange(SizePreferenceKey.self, perform: onChange)
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

#Preview("Collapsed") {
    OpenCodeOutgoingMessage(
        text: outgoingMessageLongPreviewText,
        metadataLabel: "Build · DeepSeek V4 Flash · 9:05 PM",
        onRestoreClick: {}
    )
        .padding()
        .openCodeTheme()
        .preferredColorScheme(.dark)
}

#Preview("Expanded") {
    OpenCodeOutgoingMessage(
        text: outgoingMessageLongPreviewText,
        metadataLabel: "Build · DeepSeek V4 Flash · 9:05 PM",
        initiallyExpanded: true,
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
