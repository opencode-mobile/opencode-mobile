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
    /// Hardware-keyboard Return: when set, Return runs this and Shift+Return adds a line
    /// (see `OpenCodeReturnKeyPolicy`). The soft keyboard's Return still adds a line.
    var returnKey: OpenCodeHardwareReturnKey?
    /// Tab or Shift+Tab (`backward`) left the field. Tab never types a tab character.
    var onTabOut: ((_ backward: Bool) -> Void)?
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
            returnKey: returnKey,
            onTabOut: onTabOut,
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
    let returnKey: OpenCodeHardwareReturnKey?
    let onTabOut: ((_ backward: Bool) -> Void)?
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
        uiView.returnKey = returnKey
        uiView.onTabOut = onTabOut
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
        func textView(_ textView: UITextView, shouldChangeTextIn _: NSRange, replacementText: String) -> Bool {
            if (textView as? PlaceholderTextView)?.consumeHardwareReturn(replacementText: replacementText) == true {
                return false
            }
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

/// Hardware-keyboard Return for a multi-line field: Return submits, Shift+Return adds a line.
struct OpenCodeHardwareReturnKey {
    /// Decides from the live text whether Return submits; when it does not, Return
    /// does nothing (and adds no line), like a disabled Send.
    var rule: OpenCodeReturnKeyRule
    var submit: () -> Void

    init(rule: OpenCodeReturnKeyRule, submit: @escaping () -> Void) {
        self.rule = rule
        self.submit = submit
    }

    /// Return always submits (a field with no other send conditions).
    init(submit: @escaping () -> Void) {
        self.init(rule: .fixed(true), submit: submit)
    }
}

final class PlaceholderTextView: UITextView, OpenCodeComposerTextInput, OpenCodeEscapeHandlingTextInput {
    let placeholderLabel = UILabel()
    var onDeleteBackwardWhenEmpty: (() -> Void)?
    var onPasteAttachments: (([NSItemProvider]) -> Void)?
    var returnKey: OpenCodeHardwareReturnKey?
    /// Tab or Shift+Tab (`backward`) left the field; nil just stops editing.
    var onTabOut: ((_ backward: Bool) -> Void)?
    /// Esc while editing; nil leaves the field the default way (see `OpenCodeEscapeRouter`).
    /// A field with its own Esc hands keyboard focus on explicitly, so Tab does not
    /// move into it while it is not being edited.
    var onEscape: (() -> Void)?

    /// Set for a moment once Esc or Return handed focus on: the field's focus binding
    /// would otherwise give it first responder straight back. A touch clears it.
    private var refusesFocusUntil: Date?
    private var refusesFocus: Bool { refusesFocusUntil.map { Date() < $0 } ?? false }

    override var canBecomeFocused: Bool {
        onEscape == nil || isFirstResponder ? super.canBecomeFocused : false
    }

    override var canBecomeFirstResponder: Bool {
        refusesFocus ? false : super.canBecomeFirstResponder
    }


    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        refusesFocusUntil = nil
        super.touchesBegan(touches, with: event)
    }

    /// Esc while editing: a field with its own Esc stops editing and runs it.
    func handleEscape() -> Bool {
        guard let onEscape else { return false }
        refuseFocusBriefly()
        onEscape()
        return true
    }
    /// Tab presses this view handled itself, so their release is not passed on either.
    private var swallowedPresses: Set<UIKeyboardHIDUsage> = []
    /// When a hardware Return (no Shift or other modifier) went to the text system.
    /// Its "\n" arrives after any keys typed before it, and can arrive after keys
    /// pressed after it, so the send is decided there, from the full text
    /// (`consumeHardwareReturn`). A Return that never produces a line (an input
    /// method used it) expires.
    private var pendingHardwareReturnAt: Date?
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

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if presses.count == 1, let key = presses.first?.key, key.keyCode == .keyboardTab,
           OpenCodeTabKeyPolicy.leavesField(
               otherModifiers: !key.modifierFlags.isDisjoint(with: [.command, .alternate, .control]),
               composing: markedTextRange != nil
           ) {
            swallowedPresses.insert(key.keyCode)
            leaveOnTab(backward: key.modifierFlags.contains(.shift))
            return
        }
        if presses.count == 1, let key = presses.first?.key,
           key.keyCode == .keyboardReturnOrEnter || key.keyCode == .keypadEnter,
           returnKey != nil,
           !key.modifierFlags.contains(.shift),
           key.modifierFlags.isDisjoint(with: [.command, .alternate, .control]),
           markedTextRange == nil {
            pendingHardwareReturnAt = Date()
        }
        super.pressesBegan(presses, with: event)
    }

    /// Tab right after Return: the Return's line may still be on its way, and
    /// leaving the field now would drop the send. Leave once it has arrived.
    private func leaveOnTab(backward: Bool, attempt: Int = 0) {
        if hasPendingHardwareReturn, attempt < 10 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
                self?.leaveOnTab(backward: backward, attempt: attempt + 1)
            }
            return
        }
        refuseFocusBriefly()
        if let onTabOut {
            onTabOut(backward)
        } else {
            OpenCodeKeyboardAnchor.takeFocus()
        }
    }

    private var hasPendingHardwareReturn: Bool {
        OpenCodeReturnKeyPolicy.pendingReturnApplies(pressedAt: pendingHardwareReturnAt, now: Date())
    }

    /// Call first from `textView(_:shouldChangeTextIn:replacementText:)`. True when
    /// the edit is a hardware Return this view handled (send or ignore): the
    /// delegate then refuses the edit, so no line is added.
    func consumeHardwareReturn(replacementText: String) -> Bool {
        guard replacementText == "\n", hasPendingHardwareReturn, let returnKey else { return false }
        pendingHardwareReturnAt = nil
        let liveText = text ?? ""
        let action = OpenCodeReturnKeyPolicy.action(
            shift: false,
            otherModifiers: false,
            composing: markedTextRange != nil,
            submitEnabled: returnKey.rule.submitEnabled(text: liveText)
        )
        switch action {
        case .passThrough:
            return false
        case .ignore:
            return true
        case .submit:
            // Report the full text first, so the send uses what is on screen.
            delegate?.textViewDidChange?(self)
            // A field that hands focus on (custom answer) leaves editing; the prompt keeps it.
            if onEscape != nil { refuseFocusBriefly() }
            returnKey.submit()
            return true
        }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let passed = presses.filter { press in
            guard let code = press.key?.keyCode else { return true }
            return swallowedPresses.remove(code) == nil
        }
        if !passed.isEmpty { super.pressesEnded(passed, with: event) }
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let passed = presses.filter { press in
            guard let code = press.key?.keyCode else { return true }
            return swallowedPresses.remove(code) == nil
        }
        if !passed.isEmpty { super.pressesCancelled(passed, with: event) }
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
    private func refuseFocusBriefly() {
        refusesFocusUntil = Date().addingTimeInterval(0.5)
    }

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
