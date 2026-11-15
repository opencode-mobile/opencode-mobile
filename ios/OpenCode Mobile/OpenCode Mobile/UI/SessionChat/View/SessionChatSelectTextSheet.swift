import SwiftUI
import UIKit

/// The message whose raw text the Select text sheet shows.
struct SessionChatSelectTextTarget: Equatable {
    let id: String
    /// Exactly what "Copy message" copies.
    let text: String
    /// The code block the Message sheet was opened from; the sheet opens with it in view.
    var codeBlock: String? = nil
    /// Opened from the Show full message row of a cut message: titled Full message.
    var isFullMessage = false

    /// The whole text of a message the timeline shows cut.
    static func fullMessage(id: String, text: String) -> SessionChatSelectTextTarget {
        SessionChatSelectTextTarget(id: id, text: text, isFullMessage: true)
    }
}

private struct OpenFullMessageKey: EnvironmentKey {
    static let defaultValue: ((SessionChatSelectTextTarget) -> Void)? = nil
}

extension EnvironmentValues {
    /// Opens the Select text sheet over a cut message's whole text (#1043).
    var openFullMessage: ((SessionChatSelectTextTarget) -> Void)? {
        get { self[OpenFullMessageKey.self] }
        set { self[OpenFullMessageKey.self] = newValue }
    }
}

enum SessionChatSelectText {
    /// Text larger than this (UTF-8 bytes, read without a scan) opens in a text view that
    /// scrolls itself in a half-height sheet, instead of one view as tall as the text: a pasted
    /// log of 190,000 lines must not lay out or measure the whole text at once (#1043).
    static let selfScrollingThresholdBytes = 32 * 1_024

    static func scrollsItself(_ text: String) -> Bool {
        text.utf8.count > selfScrollingThresholdBytes
    }

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
                title: title,
                scrollTargetID: focusFrame == nil || scrollsItself ? nil : Self.focusID,
                height: scrollsItself ? .half : .fitAllContent,
                content: {
                    if let target, scrollsItself {
                        SessionChatScrollingSelectTextBody(
                            text: target.text,
                            focusRange: SessionChatSelectText.focusRange(
                                text: target.text,
                                codeBlock: target.codeBlock
                            )
                        )
                        .openCodeBottomSheetContentInset()
                    } else if let target {
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

    private var scrollsItself: Bool {
        target.map { SessionChatSelectText.scrollsItself($0.text) } ?? false
    }

    private var title: OpenCodeText {
        target?.isFullMessage == true
            ? "session_chat_sheet.select_text.full_message_title"
            : "session_chat_sheet.select_text.title"
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

/// A very long text in a read-only `UITextView` that scrolls itself and fills the sheet body,
/// so TextKit lays out only what is in view. Same font, colour and selection as the short body.
private struct SessionChatScrollingSelectTextBody: View {
    let text: String
    let focusRange: NSRange?

    @Environment(\.openCodeBottomSheetBodyHeight) private var bodyHeight

    private static let bottomPadding: CGFloat = 8
    /// Until the sheet reports its body, a height no half sheet body exceeds.
    private static let provisionalHeight: CGFloat = 320

    var body: some View {
        SessionChatScrollingSelectableText(text: text, focusRange: focusRange)
            .frame(maxWidth: .infinity)
            .frame(height: max((bodyHeight ?? Self.provisionalHeight) - Self.bottomPadding, 1))
            .padding(.bottom, Self.bottomPadding)
    }
}

private struct SessionChatScrollingSelectableText: UIViewRepresentable {
    let text: String
    let focusRange: NSRange?

    final class Coordinator {
        var text: String?
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView(usingTextLayoutManager: true)
        SessionChatSelectableText.configure(view)
        view.isScrollEnabled = true
        view.alwaysBounceVertical = false
        view.showsVerticalScrollIndicator = true
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        view.textColor = UIColor(OpenCodeColors.textBase)
        view.tintColor = UIColor(OpenCodeColors.interactive)
        // Same storage compares in constant time; the text is only set once per target.
        guard context.coordinator.text != text else { return }
        context.coordinator.text = text
        view.text = text
        view.setContentOffset(.zero, animated: false)
        if let focusRange {
            DispatchQueue.main.async {
                view.scrollRangeToVisible(focusRange)
            }
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        guard let width = proposal.width, width > 0, width.isFinite,
              let height = proposal.height, height > 0, height.isFinite
        else { return nil }
        return CGSize(width: width, height: height)
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
        Self.configure(view)
        view.isScrollEnabled = false
        return view
    }

    /// Read-only, selectable, body font: shared by the short and the self-scrolling body.
    static func configure(_ view: UITextView) {
        view.isEditable = false
        view.isSelectable = true
        view.dataDetectorTypes = []
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.adjustsFontForContentSizeCategory = true
        view.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .systemFont(ofSize: 16, weight: .regular))
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.accessibilityIdentifier = "session_chat.select_text.text"
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
