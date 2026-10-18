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

    @FocusState private var focused: Bool

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
        maxLines: Int? = nil
    ) {
        let maxLines = maxLines ?? minLines
        precondition(minLines >= 1 && minLines <= maxLines, "OpenCodeTextInput needs 1 <= minLines <= maxLines")
        precondition(maxLines == 1 || kind == .text, "Only text inputs can be multi-line")
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
                        .stroke(borderColor, lineWidth: focused || errorMessage != nil ? 2 : 1)
                }
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.6)

            if let errorMessage {
                Text(errorMessage)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onChange(of: focused) { _, value in
            if isFocused?.wrappedValue != value {
                isFocused?.wrappedValue = value
            }
        }
        .onChange(of: isFocused?.wrappedValue, initial: true) { _, value in
            if let value, focused != value {
                focused = value
            }
        }
    }

    private var field: some View {
        Group {
            if kind == .password {
                SecureField(hint, text: $text)
                    .textContentType(.password)
            } else if multiline {
                TextField(hint, text: $text, axis: .vertical)
                    .lineLimit(minLines ... maxLines)
            } else {
                TextField(hint, text: $text)
                    .textContentType(contentType)
                    .keyboardType(kind == .url ? .URL : .default)
            }
        }
        .accessibilityLabel(Self.resolveAccessibilityLabel(hint: hint, accessibilityLabel: accessibilityLabel)!)
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
        if focused { return OpenCodeColors.interactive }
        return OpenCodeColors.borderWeakerBase
    }
}

/// Return submits a single-line field; a multi-line field keeps Return for new lines.
private struct SubmitBehavior: ViewModifier {
    let enabled: Bool
    let submitAction: OpenCodeTextInputSubmitAction
    let onSubmit: (() -> Void)?

    func body(content: Content) -> some View {
        if enabled {
            content
                .submitLabel(submitAction == .next ? .next : .done)
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
