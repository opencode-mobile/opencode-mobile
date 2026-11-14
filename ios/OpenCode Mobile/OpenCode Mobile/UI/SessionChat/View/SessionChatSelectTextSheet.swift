import SwiftUI
import UIKit

/// The message whose raw text the Select text sheet shows.
struct SessionChatSelectTextTarget: Equatable {
    let id: String
    /// Exactly what "Copy message" copies.
    let text: String
    /// The code block the Message sheet was opened from; the sheet opens with it in view.
    var codeBlock: String? = nil
}

enum SessionChatSelectText {
    /// Where `codeBlock` sits in the raw message text (UTF-16), so the sheet
    /// can bring it into view. Falls back to the block's first non-blank line
    /// when the raw text indents or wraps the block differently.
    static func focusRange(text: String, codeBlock: String?) -> NSRange? {
        guard let codeBlock, !codeBlock.isEmpty else { return nil }
        let raw = text as NSString
        let whole = raw.range(of: codeBlock)
        if whole.location != NSNotFound { return whole }
        let firstLine = codeBlock
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty }
        guard let firstLine else { return nil }
        let line = raw.range(of: firstLine)
        return line.location == NSNotFound ? nil : line
    }
}

extension View {
    /// Sheet with a message's raw text in one selectable text view, for
    /// selecting part of it, plus Copy all.
    func sessionChatSelectTextSheet(target: Binding<SessionChatSelectTextTarget?>) -> some View {
        modifier(SessionChatSelectTextSheetModifier(target: target))
    }
}

private struct SessionChatSelectTextSheetModifier: ViewModifier {
    @Binding var target: SessionChatSelectTextTarget?
    /// Where the code block to show sits in the body, once the text has laid out.
    @State private var focusFrame: SessionChatSelectTextFocusFrame?

    private static let focusID = "session_chat.select_text.focus"

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: Binding(
                    get: { target != nil },
                    set: { presented in
                        if !presented {
                            logAction(ProductAnalytics.Action.selectTextDismiss)
                            target = nil
                        }
                    }
                ),
                title: "session_chat_sheet.select_text.title",
                scrollTargetID: focusFrame == nil ? nil : Self.focusID,
                height: .fitAllContent,
                content: {
                    if let target {
                        SessionChatSelectTextBody(
                            text: target.text,
                            focusRange: SessionChatSelectText.focusRange(
                                text: target.text,
                                codeBlock: target.codeBlock
                            ),
                            focusFrame: focusFrame,
                            focusID: Self.focusID,
                            onFocusFrameChange: { focusFrame = $0 }
                        )
                        .openCodeBottomSheetContentInset()
                    }
                },
                pinnedBottom: {
                    if let target {
                        OpenCodeButton(variant: .fullWidthText(title: "session_chat_sheet.select_text.copy_all")) {
                            logAction(ProductAnalytics.Action.selectTextCopyAll)
                            UIPasteboard.general.string = target.text
                            self.target = nil
                        }
                        .accessibilityIdentifier("session_chat.select_text.copy_all")
                    }
                }
            )
            .onChange(of: target?.id) { _, _ in
                focusFrame = nil
            }
    }

    /// Message text stays out of Analytics; only the taxonomy action is reported.
    private func logAction(_ actionId: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionSelectText,
            actionId: actionId
        )
    }
}

/// Vertical span of the focused range inside the text view.
struct SessionChatSelectTextFocusFrame: Equatable {
    let minY: CGFloat
    let height: CGFloat
}

/// The text view at its full height (the sheet scrolls it), with an invisible
/// marker over the focused range for the sheet to scroll to.
private struct SessionChatSelectTextBody: View {
    let text: String
    let focusRange: NSRange?
    let focusFrame: SessionChatSelectTextFocusFrame?
    let focusID: String
    let onFocusFrameChange: (SessionChatSelectTextFocusFrame?) -> Void

    /// The sheet centers the marker, so a tall block is marked by its start only.
    private static let maxMarkerHeight: CGFloat = 160

    var body: some View {
        SessionChatSelectableText(text: text, focusRange: focusRange, onFocusFrameChange: onFocusFrameChange)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.bottom, 8)
            .overlay(alignment: .top) {
                if let focusFrame {
                    VStack(spacing: 0) {
                        Color.clear.frame(height: max(focusFrame.minY, 0))
                        Color.clear
                            .frame(height: min(max(focusFrame.height, 1), Self.maxMarkerHeight))
                            .id(focusID)
                    }
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }
            }
    }
}

/// Read-only `UITextView`, for range selection with handles: SwiftUI
/// `Text.textSelection` only copies the whole text on iOS. It does not scroll
/// and sizes to its text, so the sheet body scrolls it; the sheet leaves drags
/// that start in a text view alone, so selecting never moves the card.
private struct SessionChatSelectableText: UIViewRepresentable {
    let text: String
    let focusRange: NSRange?
    let onFocusFrameChange: (SessionChatSelectTextFocusFrame?) -> Void

    func makeUIView(context: Context) -> SessionChatSelectableTextView {
        let view = SessionChatSelectableTextView(usingTextLayoutManager: true)
        view.isEditable = false
        view.isSelectable = true
        view.isScrollEnabled = false
        view.dataDetectorTypes = []
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.adjustsFontForContentSizeCategory = true
        view.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .systemFont(ofSize: 16, weight: .regular))
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.accessibilityIdentifier = "session_chat.select_text.text"
        return view
    }

    func updateUIView(_ view: SessionChatSelectableTextView, context: Context) {
        view.textColor = UIColor(OpenCodeColors.textBase)
        view.tintColor = UIColor(OpenCodeColors.interactive)
        if view.text != text {
            view.text = text
        }
        view.onFocusFrameChange = onFocusFrameChange
        view.setFocusRange(focusRange)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: SessionChatSelectableTextView, context: Context) -> CGSize? {
        guard let width = proposal.width, width > 0, width.isFinite else { return nil }
        let fitted = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: ceil(fitted.height))
    }
}

final class SessionChatSelectableTextView: UITextView {
    var onFocusFrameChange: ((SessionChatSelectTextFocusFrame?) -> Void)?

    private var focusRange: NSRange?
    private var reportedFrame: SessionChatSelectTextFocusFrame?
    private var reportedWidth: CGFloat = 0

    func setFocusRange(_ range: NSRange?) {
        guard range != focusRange else { return }
        focusRange = range
        reportedWidth = 0
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        reportFocusFrameIfNeeded()
    }

    /// Reports where the focused range sits, once per width: the range moves
    /// when the text wraps differently.
    private func reportFocusFrameIfNeeded() {
        guard bounds.width > 0, bounds.width != reportedWidth else { return }
        reportedWidth = bounds.width
        let frame = focusFrame()
        guard frame != reportedFrame else { return }
        reportedFrame = frame
        // Layout runs inside a SwiftUI update; state changes wait for the next turn.
        DispatchQueue.main.async { [weak self] in
            self?.onFocusFrameChange?(frame)
        }
    }

    private func focusFrame() -> SessionChatSelectTextFocusFrame? {
        guard let focusRange,
              let start = position(from: beginningOfDocument, offset: focusRange.location),
              let end = position(from: start, offset: focusRange.length)
        else { return nil }
        let top = caretRect(for: start)
        let bottom = caretRect(for: end)
        guard !top.isNull, !bottom.isNull, top.minY.isFinite, bottom.maxY.isFinite else { return nil }
        return SessionChatSelectTextFocusFrame(
            minY: top.minY.rounded(),
            height: max(bottom.maxY - top.minY, top.height).rounded()
        )
    }
}
