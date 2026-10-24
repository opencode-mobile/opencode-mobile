import SwiftUI

enum OpenCodeTextInputKind: CaseIterable {
    case text
    case url
    case username
    case password
    /// Filter field: no sentence capitalization or autocorrect.
    case filter
}

enum OpenCodeTextInputSubmitAction {
    case next
    case done
    /// Return opens what the field names (a folder, a page) rather than finishing the form.
    case go
}

struct OpenCodeTextInput: View {
    @Binding var text: String
    var hint: String = ""
    var accessibilityLabel: String?
    var kind: OpenCodeTextInputKind = .text
    var enabled = true
    var errorMessage: String?
    var submitAction: OpenCodeTextInputSubmitAction = .done
    var isFocused: Binding<Bool>?
    var onSubmit: (() -> Void)?
    /// Above 1, the field is multi-line: it starts this tall, Return adds a line,
    /// and `submitAction` / `onSubmit` are unused.
    var minLines = 1
    /// The field grows up to this many lines, then scrolls.
    var maxLines = 1
    /// Multi-line fields only: refuses typed and pasted text past this many
    /// characters; an `N/max` count shows near the limit.
    var maxLength: Int?

    @FocusState private var focused: Bool
    /// Focus of the multi-line field, which is UIKit-backed and outside `@FocusState`.
    @State private var multilineFocused = false

    init(
        text: Binding<String>,
        hint: String = "",
        accessibilityLabel: String? = nil,
        kind: OpenCodeTextInputKind = .text,
        enabled: Bool = true,
        errorMessage: String? = nil,
        submitAction: OpenCodeTextInputSubmitAction = .done,
        isFocused: Binding<Bool>? = nil,
        onSubmit: (() -> Void)? = nil,
        minLines: Int = 1,
        maxLines: Int? = nil,
        maxLength: Int? = nil
    ) {
        let maxLines = maxLines ?? minLines
        precondition(minLines >= 1 && minLines <= maxLines, "OpenCodeTextInput needs 1 <= minLines <= maxLines")
        precondition(maxLines == 1 || kind == .text, "Only text inputs can be multi-line")
        precondition(maxLength == nil || maxLines > 1, "maxLength needs a multi-line input")
        precondition(
            Self.resolveAccessibilityLabel(hint: hint, accessibilityLabel: accessibilityLabel) != nil,
            "OpenCodeTextInput requires a non-blank hint or accessibilityLabel"
        )
        _text = text
        self.hint = hint
        self.accessibilityLabel = accessibilityLabel
        self.kind = kind
        self.enabled = enabled
        self.errorMessage = errorMessage
        self.submitAction = submitAction
        self.isFocused = isFocused
        self.onSubmit = onSubmit
        self.minLines = minLines
        self.maxLines = maxLines
        self.maxLength = maxLength
    }

    private var multiline: Bool { maxLines > 1 }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            field
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .textInputAutocapitalization(kind == .text ? .sentences : .never)
                .autocorrectionDisabled(kind != .text)
                .focused($focused)
                .modifier(SubmitBehavior(enabled: !multiline, submitAction: submitAction, onSubmit: onSubmit))
                .padding(.horizontal, 12)
                .padding(.vertical, multiline ? 12 : 0)
                .frame(minHeight: 48, alignment: multiline ? .topLeading : .leading)
                .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 8))
                .overlay {
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(borderColor, lineWidth: isEditing || errorMessage != nil ? 2 : 1)
                }
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.6)

            if let errorMessage {
                Text(errorMessage)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
            }
            if let lengthLabel = Self.lengthLabel(length: text.count, maxLength: maxLength) {
                Text(lengthLabel)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .monospacedDigit()
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onChange(of: isEditing) { _, value in
            if isFocused?.wrappedValue != value {
                isFocused?.wrappedValue = value
            }
        }
        .onChange(of: isFocused?.wrappedValue, initial: true) { _, value in
            guard let value else { return }
            if multiline {
                if multilineFocused != value { multilineFocused = value }
            } else if focused != value {
                focused = value
            }
        }
    }

    private var isEditing: Bool { multiline ? multilineFocused : focused }

    private var field: some View {
        Group {
            if kind == .password {
                SecureField(hint, text: $text)
                    .textContentType(.password)
            } else if multiline {
                OpenCodeMultilineTextView(
                    text: $text,
                    focused: $multilineFocused,
                    placeholder: hint,
                    minLines: minLines,
                    maxLines: maxLines,
                    maxLength: maxLength,
                    enabled: enabled
                )
            } else {
                TextField(hint, text: $text)
                    .textContentType(contentType)
                    .keyboardType(kind == .url ? .URL : .default)
            }
        }
        .accessibilityLabel(Self.resolveAccessibilityLabel(hint: hint, accessibilityLabel: accessibilityLabel)!)
    }

    /// What an edit of `range` to `replacement` leaves in `text`, keeping within
    /// `maxLength`: the replacement is cut, so a long paste keeps its start.
    /// `nil` when the edit adds nothing that fits.
    static func limitedEdit(
        of text: String,
        replacing range: Range<String.Index>,
        with replacement: String,
        maxLength: Int
    ) -> (text: String, inserted: String)? {
        let room = maxLength - (text.count - text[range].count)
        guard room > 0 || replacement.isEmpty else { return nil }
        let inserted = String(replacement.prefix(max(room, 0)))
        return (text.replacingCharacters(in: range, with: inserted), inserted)
    }

    /// `N/max` once the text reaches 90% of `maxLength`, so the cap is never a surprise.
    static func lengthLabel(length: Int, maxLength: Int?) -> String? {
        guard let maxLength, length * 10 >= maxLength * 9 else { return nil }
        return "\(length)/\(maxLength)"
    }

    static func resolveAccessibilityLabel(hint: String?, accessibilityLabel: String?) -> String? {
        if let accessibilityLabel, !accessibilityLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return accessibilityLabel
        }
        if let hint, !hint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return hint }
        return nil
    }

    private var contentType: UITextContentType? {
        switch kind {
        case .url: .URL
        case .username: .username
        case .text, .password, .filter: nil
        }
    }

    private var borderColor: Color {
        if errorMessage != nil { return OpenCodeColors.error }
        if isEditing { return OpenCodeColors.interactive }
        return OpenCodeColors.borderWeakerBase
    }
}

/// Return submits a single-line field; a multi-line field keeps Return for new lines.
private struct SubmitBehavior: ViewModifier {
    let enabled: Bool
    let submitAction: OpenCodeTextInputSubmitAction
    let onSubmit: (() -> Void)?

    private var submitLabel: SubmitLabel {
        switch submitAction {
        case .next: .next
        case .done: .done
        case .go: .go
        }
    }

    func body(content: Content) -> some View {
        if enabled {
            content
                .submitLabel(submitLabel)
                .onSubmit { onSubmit?() }
        } else {
            content
        }
    }
}

#Preview("Text Input") {
    OpenCodeTextInput(text: .constant(""), hint: "Server URL", kind: .url)
        .padding()
        .openCodeTheme()
}

/// The multi-line field. UIKit-backed because `UITextView` can refuse an edit
/// past `maxLength` before it lands; SwiftUI's vertical `TextField` shows the
/// extra text even when the bound value is clipped.
private struct OpenCodeMultilineTextView: UIViewRepresentable {
    @Binding var text: String
    @Binding var focused: Bool
    let placeholder: String
    let minLines: Int
    let maxLines: Int
    let maxLength: Int?
    let enabled: Bool

    private static let font = UIFont.systemFont(ofSize: 16, weight: .regular)

    func makeUIView(context: Context) -> PlaceholderTextView {
        let view = PlaceholderTextView()
        view.backgroundColor = .clear
        view.font = Self.font
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.isScrollEnabled = true
        view.alwaysBounceVertical = false
        view.autocapitalizationType = .sentences
        view.setContentHuggingPriority(.defaultLow, for: .vertical)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        view.delegate = context.coordinator
        view.placeholderLabel.font = Self.font
        return view
    }

    func updateUIView(_ view: PlaceholderTextView, context: Context) {
        context.coordinator.parent = self
        view.textColor = UIColor(OpenCodeColors.textStrong)
        view.placeholderLabel.text = placeholder
        view.placeholderLabel.textColor = UIColor(OpenCodeColors.textWeaker)
        view.isEditable = enabled
        if view.text != text {
            view.text = text
            view.placeholderLabel.isHidden = !text.isEmpty
        }
        // Act only when the requested focus changes, so an update that lands
        // mid-tap doesn't undo the user's own focus.
        if context.coordinator.appliedFocus != focused {
            context.coordinator.appliedFocus = focused
            if focused {
                view.requestFocus()
            } else if view.isFirstResponder {
                DispatchQueue.main.async { view.resignFirstResponder() }
            }
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: PlaceholderTextView, context: Context) -> CGSize? {
        guard let width = proposal.width, width.isFinite else { return nil }
        let lineHeight = Self.font.lineHeight
        let content = (text.isEmpty ? " " : text as NSString).boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: Self.font],
            context: nil
        ).height
        let height = min(max(ceil(content), lineHeight * CGFloat(minLines)), lineHeight * CGFloat(maxLines))
        return CGSize(width: width, height: height)
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: OpenCodeMultilineTextView
        var appliedFocus: Bool

        init(parent: OpenCodeMultilineTextView) {
            self.parent = parent
            appliedFocus = parent.focused
        }

        func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText: String) -> Bool {
            guard let maxLength = parent.maxLength,
                  let current = textView.text,
                  let swiftRange = Range(range, in: current)
            else { return true }
            if current.count - current[swiftRange].count + replacementText.count <= maxLength { return true }
            guard let edit = OpenCodeTextInput.limitedEdit(
                of: current,
                replacing: swiftRange,
                with: replacementText,
                maxLength: maxLength
            ) else { return false }
            // Insert only what fits, then report it as an ordinary edit.
            textView.text = edit.text
            textView.selectedRange = NSRange(location: range.location + edit.inserted.utf16.count, length: 0)
            textViewDidChange(textView)
            return false
        }

        func textViewDidChange(_ textView: UITextView) {
            let value = textView.text ?? ""
            (textView as? PlaceholderTextView)?.placeholderLabel.isHidden = !value.isEmpty
            if parent.text != value { parent.text = value }
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            appliedFocus = true
            if !parent.focused { parent.focused = true }
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            appliedFocus = false
            if parent.focused { parent.focused = false }
        }
    }
}
