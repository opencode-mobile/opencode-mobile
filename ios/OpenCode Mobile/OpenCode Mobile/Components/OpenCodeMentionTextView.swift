import SwiftUI
import UIKit

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
    let onChange: (_ text: String, _ selectionStart: Int, _ selectionEnd: Int) -> Void

    var body: some View {
        Representable(
            text: text,
            mentionRanges: mentionRanges,
            selectionStart: selectionStart,
            selectionEnd: selectionEnd,
            selectionRevision: selectionRevision,
            placeholder: placeholder,
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
    let onChange: (String, Int, Int) -> Void

    func makeUIView(context: Context) -> PlaceholderTextView {
        let view = PlaceholderTextView()
        view.backgroundColor = .clear
        view.font = Self.font
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.isScrollEnabled = false
        view.delegate = context.coordinator
        view.placeholderLabel.font = Self.font
        return view
    }

    func updateUIView(_ uiView: PlaceholderTextView, context: Context) {
        let resolvedTextColor = UIColor(OpenCodeColors.textStrong)
        let resolvedMentionColor = UIColor(OpenCodeColors.syntaxType)
        uiView.placeholderLabel.text = placeholder
        uiView.placeholderLabel.textColor = UIColor(OpenCodeColors.textWeak)
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
        let width = uiView.bounds.width
        if width > 0 {
            let fitting = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
            uiView.isScrollEnabled = fitting.height > Self.maxCollapsedHeight + 0.5
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
        let fitting = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        let idealHeight = max(fitting.height, Self.font.lineHeight)
        // Cap growth so chips/send stay above the keyboard; further text scrolls
        // inside the field (OPE-118).
        return CGSize(width: width, height: min(idealHeight, Self.maxCollapsedHeight))
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onChange: onChange)
    }

    fileprivate static let font = UIFont.systemFont(ofSize: 16, weight: .regular)
    /// ~5 body lines — enough for a long prompt without swallowing the chat.
    fileprivate static let maxCollapsedHeight: CGFloat = font.lineHeight * 5

    final class Coordinator: NSObject, UITextViewDelegate {
        private let onChange: (String, Int, Int) -> Void
        private var lastRenderedText: String?
        private var lastRenderedMentionRanges: [Range<Int>] = []
        private var lastAppliedSelectionRevision: Int?

        init(onChange: @escaping (String, Int, Int) -> Void) {
            self.onChange = onChange
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
            if lastRenderedText != text || lastRenderedMentionRanges != mentionRanges {
                view.attributedText = Self.attributedString(
                    text: text,
                    mentionRanges: mentionRanges,
                    textColor: textColor,
                    mentionColor: mentionColor,
                    font: Representable.font
                )
                lastRenderedText = text
                lastRenderedMentionRanges = mentionRanges
            }
            if lastAppliedSelectionRevision != selectionRevision {
                lastAppliedSelectionRevision = selectionRevision
                applySelection(start: selectionStart, end: selectionEnd, text: text, to: view)
            }
            view.placeholderLabel.isHidden = !text.isEmpty
        }

        func textViewDidChange(_ textView: UITextView) {
            let text = textView.text ?? ""
            lastRenderedText = text
            (textView as? PlaceholderTextView)?.placeholderLabel.isHidden = !text.isEmpty
            reportSelection(text: text, utf16Range: textView.selectedRange)
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

        private static func attributedString(
            text: String,
            mentionRanges: [Range<Int>],
            textColor: UIColor,
            mentionColor: UIColor,
            font: UIFont
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
    }
}

final class PlaceholderTextView: UITextView {
    let placeholderLabel = UILabel()

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
