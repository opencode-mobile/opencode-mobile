import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Prompt-field height for `OpenCodeMentionTextView`. Height is measured from
/// attributed text, not `UITextView.sizeThatFits`: once scroll is on,
/// `sizeThatFits` returns the capped bounds and the next update turns scroll
/// back off (OPE-213).
enum OpenCodeMentionTextViewLayout {
    static let font = UIFont.systemFont(ofSize: 16, weight: .regular)
    /// ~5 body lines — enough for a long prompt without swallowing the chat.
    static var maxCollapsedHeight: CGFloat { font.lineHeight * 5 }
    static let scrollThreshold: CGFloat = 0.5

    static func attributedString(
        text: String,
        mentionRanges: [Range<Int>],
        textColor: UIColor,
        mentionColor: UIColor
    ) -> NSAttributedString {
        let result = NSMutableAttributedString(
            string: text,
            attributes: [.foregroundColor: textColor, .font: font]
        )
        for range in mentionRanges.sorted(by: { $0.lowerBound < $1.lowerBound }) {
            guard range.lowerBound >= 0, range.upperBound <= text.count, range.lowerBound < range.upperBound,
                  let nsRange = text.nsRange(fromCharacterRange: range)
            else { continue }
            result.addAttribute(.foregroundColor, value: mentionColor, range: nsRange)
        }
        return result
    }

    static func contentHeight(for attributedText: NSAttributedString, width: CGFloat) -> CGFloat {
        guard width > 0 else { return font.lineHeight }
        guard attributedText.length > 0 else { return font.lineHeight }
        let rect = attributedText.boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )
        return max(ceil(rect.height), font.lineHeight)
    }

    static func clampedHeight(contentHeight: CGFloat) -> CGFloat {
        min(max(contentHeight, font.lineHeight), maxCollapsedHeight)
    }

    static func shouldScroll(contentHeight: CGFloat) -> Bool {
        contentHeight > maxCollapsedHeight + scrollThreshold
    }
}

/// Multi-line text input that renders `@name` agent-mention spans inline and
/// reports caret/selection changes alongside text changes. `UITextView` is used
/// instead of SwiftUI's `TextField` because the composer needs attributed-range
/// styling plus precise, externally-driven caret placement (mention insert,
/// atomic mention delete, restore, clear) that `TextField` cannot express.
struct OpenCodeMentionTextView: View {
    let text: String
    let mentionRanges: [Range<Int>]
    let selectionStart: Int?
    let selectionEnd: Int?
    let selectionRevision: Int
    let placeholder: String
    /// Changing the value moves keyboard focus into the field. The value present
    /// when the field is created never focuses it.
    var focusRequestToken: Int = 0
    /// Called with true when the field starts editing and false when it stops.
    var onFocusChange: ((Bool) -> Void)?
    /// Turns off autocorrect, auto-capitalization and smart quotes and dashes, for text that
    /// must reach the server exactly as typed, such as a shell command (`--flag` stays `--flag`).
    var literalInput = false
    /// Delete on an empty field, which changes no text: the composer leaves a mode with it.
    var onDeleteBackwardWhenEmpty: (() -> Void)?
    /// When set, Paste (menu or ⌘V) of an image or file hands its item providers here and inserts no
    /// text. A text-only clipboard always pastes as text.
    var onPasteAttachments: (([NSItemProvider]) -> Void)?
    /// False while an image or file is dragged over the screen, so the field's own text drop does
    /// not take the drop and the screen's drop target gets it (#870).
    var acceptsTextDrops = true
    let onChange: (_ text: String, _ selectionStart: Int, _ selectionEnd: Int) -> Void

    var body: some View {
        Representable(
            text: text,
            mentionRanges: mentionRanges,
            selectionStart: selectionStart,
            selectionEnd: selectionEnd,
            selectionRevision: selectionRevision,
            placeholder: placeholder,
            focusRequestToken: focusRequestToken,
            onFocusChange: onFocusChange,
            literalInput: literalInput,
            onDeleteBackwardWhenEmpty: onDeleteBackwardWhenEmpty,
            onPasteAttachments: onPasteAttachments,
            acceptsTextDrops: acceptsTextDrops,
            onChange: onChange
        )
    }
}

private struct Representable: UIViewRepresentable {
    let text: String
    let mentionRanges: [Range<Int>]
    let selectionStart: Int?
    let selectionEnd: Int?
    let selectionRevision: Int
    let placeholder: String
    let focusRequestToken: Int
    let onFocusChange: ((Bool) -> Void)?
    let literalInput: Bool
    let onDeleteBackwardWhenEmpty: (() -> Void)?
    let onPasteAttachments: (([NSItemProvider]) -> Void)?
    let acceptsTextDrops: Bool
    let onChange: (String, Int, Int) -> Void

    func makeUIView(context: Context) -> PlaceholderTextView {
        let view = PlaceholderTextView()
        view.backgroundColor = .clear
        view.font = OpenCodeMentionTextViewLayout.font
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        // Keep scroll on. Toggling it from `sizeThatFits` while measuring the
        // live view turns it back off on the next SwiftUI update (OPE-213).
        view.isScrollEnabled = true
        view.alwaysBounceVertical = false
        view.bounces = false
        view.setContentHuggingPriority(.defaultLow, for: .vertical)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        view.delegate = context.coordinator
        view.placeholderLabel.font = OpenCodeMentionTextViewLayout.font
        return view
    }

    func updateUIView(_ uiView: PlaceholderTextView, context: Context) {
        let resolvedTextColor = UIColor(OpenCodeColors.textStrong)
        let resolvedMentionColor = UIColor(OpenCodeColors.syntaxType)
        uiView.placeholderLabel.text = placeholder
        uiView.placeholderLabel.textColor = UIColor(OpenCodeColors.textWeak)
        context.coordinator.onFocusChange = onFocusChange
        uiView.onDeleteBackwardWhenEmpty = onDeleteBackwardWhenEmpty
        uiView.onPasteAttachments = onPasteAttachments
        uiView.acceptsTextDrops = acceptsTextDrops
        applyInputTraits(to: uiView)
        if context.coordinator.lastFocusRequestToken != focusRequestToken {
            context.coordinator.lastFocusRequestToken = focusRequestToken
            uiView.requestFocus()
        }
        context.coordinator.apply(
            text: text,
            mentionRanges: mentionRanges,
            textColor: resolvedTextColor,
            mentionColor: resolvedMentionColor,
            selectionStart: selectionStart,
            selectionEnd: selectionEnd,
            selectionRevision: selectionRevision,
            to: uiView
        )
    }

    private func applyInputTraits(to view: PlaceholderTextView) {
        let autocorrection: UITextAutocorrectionType = literalInput ? .no : .default
        guard view.autocorrectionType != autocorrection else { return }
        view.autocorrectionType = autocorrection
        view.spellCheckingType = literalInput ? .no : .default
        view.autocapitalizationType = literalInput ? .none : .sentences
        view.smartDashesType = literalInput ? .no : .default
        view.smartQuotesType = literalInput ? .no : .default
        view.smartInsertDeleteType = literalInput ? .no : .default
        // The keyboard reads these traits only when it attaches; refresh it while editing.
        if view.isFirstResponder {
            view.reloadInputViews()
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: PlaceholderTextView, context: Context) -> CGSize? {
        guard let width = proposal.width, width.isFinite else { return nil }
        // Measure from current props — `sizeThatFits` can run before `updateUIView`.
        let resolvedTextColor = UIColor(OpenCodeColors.textStrong)
        let resolvedMentionColor = UIColor(OpenCodeColors.syntaxType)
        context.coordinator.apply(
            text: text,
            mentionRanges: mentionRanges,
            textColor: resolvedTextColor,
            mentionColor: resolvedMentionColor,
            selectionStart: selectionStart,
            selectionEnd: selectionEnd,
            selectionRevision: selectionRevision,
            to: uiView
        )
        let contentHeight = OpenCodeMentionTextViewLayout.contentHeight(
            for: uiView.attributedText ?? NSAttributedString(),
            width: width
        )
        // Cap growth so chips/send stay above the keyboard; further text scrolls
        // inside the field (OPE-118 / OPE-213).
        return CGSize(width: width, height: OpenCodeMentionTextViewLayout.clampedHeight(contentHeight: contentHeight))
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(focusRequestToken: focusRequestToken, onChange: onChange)
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        private let onChange: (String, Int, Int) -> Void
        var onFocusChange: ((Bool) -> Void)?
        var lastFocusRequestToken: Int
        private var lastRenderedText: String?
        private var lastRenderedMentionRanges: [Range<Int>] = []
        private var lastAppliedSelectionRevision: Int?
        private var textColor: UIColor = .label

        init(focusRequestToken: Int, onChange: @escaping (String, Int, Int) -> Void) {
            lastFocusRequestToken = focusRequestToken
            self.onChange = onChange
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            onFocusChange?(true)
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            onFocusChange?(false)
        }

        func apply(
            text: String,
            mentionRanges: [Range<Int>],
            textColor: UIColor,
            mentionColor: UIColor,
            selectionStart: Int?,
            selectionEnd: Int?,
            selectionRevision: Int,
            to view: PlaceholderTextView
        ) {
            self.textColor = textColor
            if lastRenderedText != text || lastRenderedMentionRanges != mentionRanges {
                view.attributedText = OpenCodeMentionTextViewLayout.attributedString(
                    text: text,
                    mentionRanges: mentionRanges,
                    textColor: textColor,
                    mentionColor: mentionColor
                )
                lastRenderedText = text
                lastRenderedMentionRanges = mentionRanges
            }
            if lastAppliedSelectionRevision != selectionRevision {
                lastAppliedSelectionRevision = selectionRevision
                applySelection(start: selectionStart, end: selectionEnd, text: text, to: view)
                view.scrollCaretIntoViewIfNeeded()
            } else {
                view.retryPendingCaretScrollIfNeeded()
            }
            view.placeholderLabel.isHidden = !text.isEmpty
        }

        /// Typed text starts plain. Without this it inherits the color of the character before the
        /// caret, so typing right after a highlighted range (a mention, or the `!` of a shell
        /// command) would paint the new text too until the next full re-render.
        func textView(_ textView: UITextView, shouldChangeTextIn _: NSRange, replacementText _: String) -> Bool {
            if textView.markedTextRange == nil {
                textView.typingAttributes = [.foregroundColor: textColor, .font: OpenCodeMentionTextViewLayout.font]
            }
            return true
        }

        func textViewDidChange(_ textView: UITextView) {
            let text = textView.text ?? ""
            lastRenderedText = text
            let mentionView = textView as? PlaceholderTextView
            mentionView?.placeholderLabel.isHidden = !text.isEmpty
            reportSelection(text: text, utf16Range: textView.selectedRange)
            mentionView?.scrollCaretIntoViewIfNeeded()
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            guard textView.markedTextRange == nil else { return }
            let text = textView.text ?? ""
            guard text == lastRenderedText else { return }
            reportSelection(text: text, utf16Range: textView.selectedRange)
        }

        private func reportSelection(text: String, utf16Range: NSRange) {
            let start = text.characterOffset(fromUTF16Offset: utf16Range.location)
            let end = text.characterOffset(fromUTF16Offset: utf16Range.location + utf16Range.length)
            onChange(text, start, end)
        }

        private func applySelection(start: Int?, end: Int?, text: String, to view: UITextView) {
            let characterCount = text.count
            let startOffset = min(max(start ?? characterCount, 0), characterCount)
            let endOffset = min(max(end ?? startOffset, 0), characterCount)
            let utf16Start = text.utf16Offset(fromCharacterOffset: startOffset)
            let utf16End = text.utf16Offset(fromCharacterOffset: endOffset)
            guard let startPosition = view.position(from: view.beginningOfDocument, offset: utf16Start),
                  let endPosition = view.position(from: view.beginningOfDocument, offset: utf16End),
                  let range = view.textRange(from: startPosition, to: endPosition)
            else { return }
            view.selectedTextRange = range
        }
    }
}

final class PlaceholderTextView: UITextView, OpenCodeComposerTextInput {
    let placeholderLabel = UILabel()
    var onDeleteBackwardWhenEmpty: (() -> Void)?
    var onPasteAttachments: (([NSItemProvider]) -> Void)?
    var acceptsTextDrops = true {
        didSet {
            guard acceptsTextDrops != oldValue else { return }
            updateTextDropInteraction()
        }
    }
    private var removedTextDropInteraction: UIDropInteraction?
    private var pendingCaretScroll = false
    private var pendingFocus = false

    override init(frame: CGRect, textContainer: NSTextContainer?) {
        super.init(frame: frame, textContainer: textContainer)
        placeholderLabel.numberOfLines = 0
        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(placeholderLabel)
        NSLayoutConstraint.activate([
            placeholderLabel.topAnchor.constraint(equalTo: topAnchor),
            placeholderLabel.leadingAnchor.constraint(equalTo: leadingAnchor),
            placeholderLabel.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        retryPendingCaretScrollIfNeeded()
    }

    override func deleteBackward() {
        if text.isEmpty, let onDeleteBackwardWhenEmpty {
            onDeleteBackwardWhenEmpty()
            return
        }
        super.deleteBackward()
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(paste(_:)), onPasteAttachments != nil, Self.pasteboardHasAttachments {
            return true
        }
        return super.canPerformAction(action, withSender: sender)
    }

    /// Paste from the edit menu or ⌘V is user-initiated, so reading the pasteboard here shows no
    /// paste-permission prompt.
    override func paste(_ sender: Any?) {
        guard let onPasteAttachments, Self.pasteboardHasAttachments else {
            super.paste(sender)
            return
        }
        let providers = UIPasteboard.general.itemProviders.filter { AttachmentItemProviders.kind(of: $0) != nil }
        guard !providers.isEmpty else {
            super.paste(sender)
            return
        }
        onPasteAttachments(providers)
    }

    /// Reads types only; no pasteboard contents, so no prompt.
    private static var pasteboardHasAttachments: Bool {
        let pasteboard = UIPasteboard.general
        return pasteboard.hasImages ||
            pasteboard.contains(pasteboardTypes: [UTType.pdf.identifier, UTType.fileURL.identifier])
    }

    private func updateTextDropInteraction() {
        if acceptsTextDrops {
            if let removedTextDropInteraction {
                addInteraction(removedTextDropInteraction)
                self.removedTextDropInteraction = nil
            }
        } else if let interaction = textDropInteraction {
            removeInteraction(interaction)
            removedTextDropInteraction = interaction
        }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if pendingFocus, window != nil {
            requestFocus()
        }
    }

    /// Becomes first responder once the view is in a window. Deferred a turn so it
    /// never runs inside a SwiftUI update.
    func requestFocus() {
        guard window != nil else {
            pendingFocus = true
            return
        }
        pendingFocus = false
        DispatchQueue.main.async { [weak self] in
            guard let self, window != nil, !isFirstResponder else { return }
            becomeFirstResponder()
        }
    }

    func scrollCaretIntoViewIfNeeded() {
        guard bounds.width > 0, bounds.height > 0 else {
            pendingCaretScroll = true
            return
        }
        let contentHeight = OpenCodeMentionTextViewLayout.contentHeight(
            for: attributedText ?? NSAttributedString(),
            width: bounds.width
        )
        guard OpenCodeMentionTextViewLayout.shouldScroll(contentHeight: contentHeight) else {
            pendingCaretScroll = false
            return
        }
        pendingCaretScroll = false
        scrollRangeToVisible(selectedRange)
    }

    func retryPendingCaretScrollIfNeeded() {
        guard pendingCaretScroll else { return }
        scrollCaretIntoViewIfNeeded()
    }
}

private extension String {
    func nsRange(fromCharacterRange range: Range<Int>) -> NSRange? {
        guard range.lowerBound >= 0, range.upperBound <= count, range.lowerBound <= range.upperBound else { return nil }
        let start = index(startIndex, offsetBy: range.lowerBound)
        let end = index(startIndex, offsetBy: range.upperBound)
        return NSRange(start ..< end, in: self)
    }

    func characterOffset(fromUTF16Offset utf16Offset: Int) -> Int {
        let clamped = max(0, min(utf16Offset, utf16.count))
        guard let utf16Index = utf16.index(utf16.startIndex, offsetBy: clamped, limitedBy: utf16.endIndex),
              let stringIndex = utf16Index.samePosition(in: self)
        else {
            return count
        }
        return distance(from: startIndex, to: stringIndex)
    }

    func utf16Offset(fromCharacterOffset characterOffset: Int) -> Int {
        let clamped = max(0, min(characterOffset, count))
        let stringIndex = index(startIndex, offsetBy: clamped)
        guard let utf16Index = stringIndex.samePosition(in: utf16) else {
            return utf16.count
        }
        return utf16.distance(from: utf16.startIndex, to: utf16Index)
    }
}

#Preview("Mention text view") {
    OpenCodeMentionTextView(
        text: "Ask @explore to look into this",
        mentionRanges: [4 ..< 12],
        selectionStart: 31,
        selectionEnd: 31,
        selectionRevision: 0,
        placeholder: "Ask anything...",
        onChange: { _, _, _ in }
    )
    .padding()
    .openCodeTheme()
}

#Preview("Long prompt") {
    OpenCodeMentionTextView(
        text: String(repeating: "a very long message ", count: 24),
        mentionRanges: [],
        selectionStart: nil,
        selectionEnd: nil,
        selectionRevision: 0,
        placeholder: "Ask anything...",
        onChange: { _, _, _ in }
    )
    .frame(maxWidth: 320)
    .padding()
    .openCodeTheme()
}
