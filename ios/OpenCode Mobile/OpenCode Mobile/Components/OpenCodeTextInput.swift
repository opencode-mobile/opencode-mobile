import SwiftUI

enum OpenCodeTextInputKind: CaseIterable {
    case text
    case url
    case username
    case password
}

enum OpenCodeTextInputSubmitAction {
    case next
    case done
}

struct OpenCodeTextInput: View {
    var label: String?
    @Binding var text: String
    var placeholder: String = ""
    var accessibilityLabel: String?
    var kind: OpenCodeTextInputKind = .text
    var enabled = true
    var errorMessage: String?
    var submitAction: OpenCodeTextInputSubmitAction = .done
    var isFocused: Binding<Bool>?
    var onSubmit: (() -> Void)?

    @FocusState private var focused: Bool

    init(
        label: String? = nil,
        text: Binding<String>,
        placeholder: String = "",
        accessibilityLabel: String? = nil,
        kind: OpenCodeTextInputKind = .text,
        enabled: Bool = true,
        errorMessage: String? = nil,
        submitAction: OpenCodeTextInputSubmitAction = .done,
        isFocused: Binding<Bool>? = nil,
        onSubmit: (() -> Void)? = nil
    ) {
        precondition(
            Self.resolveAccessibilityLabel(label: label, accessibilityLabel: accessibilityLabel) != nil,
            "OpenCodeTextInput requires a visible label or a non-blank accessibilityLabel"
        )
        self.label = label
        _text = text
        self.placeholder = placeholder
        self.accessibilityLabel = accessibilityLabel
        self.kind = kind
        self.enabled = enabled
        self.errorMessage = errorMessage
        self.submitAction = submitAction
        self.isFocused = isFocused
        self.onSubmit = onSubmit
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let label {
                Text(label)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeaker)
            }

            field
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .textInputAutocapitalization(kind == .text ? .sentences : .never)
                .autocorrectionDisabled(kind != .text)
                .focused($focused)
                .submitLabel(submitAction == .next ? .next : .done)
                .onSubmit { onSubmit?() }
                .padding(.horizontal, 12)
                .frame(minHeight: 48)
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
                SecureField(placeholder, text: $text)
                    .textContentType(.password)
            } else {
                TextField(placeholder, text: $text)
                    .textContentType(contentType)
                    .keyboardType(kind == .url ? .URL : .default)
            }
        }
        .accessibilityLabel(Self.resolveAccessibilityLabel(label: label, accessibilityLabel: accessibilityLabel)!)
    }

    static func resolveAccessibilityLabel(label: String?, accessibilityLabel: String?) -> String? {
        if let accessibilityLabel, !accessibilityLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return accessibilityLabel
        }
        if let label, !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return label }
        return nil
    }

    private var contentType: UITextContentType? {
        switch kind {
        case .url: .URL
        case .username: .username
        case .text, .password: nil
        }
    }

    private var borderColor: Color {
        if errorMessage != nil { return OpenCodeColors.error }
        if focused { return OpenCodeColors.interactive }
        return OpenCodeColors.borderWeakerBase
    }
}

#Preview("Text Input") {
    OpenCodeTextInput(label: "Server URL", text: .constant("http://127.0.0.1:4096/"), kind: .url)
        .padding()
        .openCodeTheme()
}
