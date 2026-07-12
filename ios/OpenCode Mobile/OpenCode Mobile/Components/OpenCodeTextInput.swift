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
    let label: String
    @Binding var text: String
    var placeholder: String = ""
    var kind: OpenCodeTextInputKind = .text
    var enabled = true
    var errorMessage: String?
    var submitAction: OpenCodeTextInputSubmitAction = .done

    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(enabled ? OpenCodeColors.textBase : OpenCodeColors.textWeaker)

            field
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textStrong)
                .textInputAutocapitalization(kind == .text ? .sentences : .never)
                .autocorrectionDisabled(kind != .text)
                .focused($focused)
                .submitLabel(submitAction == .next ? .next : .done)
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
    }

    @ViewBuilder
    private var field: some View {
        if kind == .password {
            SecureField(placeholder, text: $text)
                .textContentType(.password)
        } else {
            TextField(placeholder, text: $text)
                .textContentType(contentType)
                .keyboardType(kind == .url ? .URL : .default)
        }
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
