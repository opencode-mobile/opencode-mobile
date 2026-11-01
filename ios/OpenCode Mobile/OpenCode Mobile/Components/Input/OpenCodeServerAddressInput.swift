import SwiftUI

struct OpenCodeServerAddressInput: View {
    @Binding var value: String
    var hint: OpenCodeText = "Host"
    var accessibilityLabel: OpenCodeText?
    var enabled = true
    var errorMessage: OpenCodeText?
    var submitAction: OpenCodeTextInputSubmitAction = .done
    var isFocused: Binding<Bool>?
    var onSubmit: (() -> Void)?

    @State private var scheme: ServerAddressScheme = .http
    @State private var hostPort: String = ""
    @State private var showSchemeSheet = false
    @FocusState private var focused: Bool

    private let cornerRadius: CGFloat = 8
    private let minHeight: CGFloat = 48
    /// `[16 [http 4 ↓] 8] 8 host 16`
    private let fieldStartPadding: CGFloat = 16
    private let fieldEndPadding: CGFloat = 16
    private let schemeEndPadding: CGFloat = 8
    private let hostStartPadding: CGFloat = 8
    private let schemeIconSize: CGFloat = 16
    private let schemeIconGap: CGFloat = 4

    init(
        value: Binding<String>,
        hint: OpenCodeText = "Host",
        accessibilityLabel: OpenCodeText? = nil,
        enabled: Bool = true,
        errorMessage: OpenCodeText? = nil,
        submitAction: OpenCodeTextInputSubmitAction = .done,
        isFocused: Binding<Bool>? = nil,
        onSubmit: (() -> Void)? = nil
    ) {
        precondition(
            OpenCodeTextInput.resolveAccessibilityLabel(hint: hint.resolved, accessibilityLabel: accessibilityLabel?.resolved) != nil,
            "OpenCodeServerAddressInput requires a non-blank hint or accessibilityLabel"
        )
        _value = value
        self.hint = hint
        self.accessibilityLabel = accessibilityLabel
        self.enabled = enabled
        self.errorMessage = errorMessage
        self.submitAction = submitAction
        self.isFocused = isFocused
        self.onSubmit = onSubmit
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 0) {
                Button {
                    showSchemeSheet = true
                } label: {
                    HStack(spacing: schemeIconGap) {
                        Text(scheme.label)
                            .font(OpenCodeTypography.body)
                            .foregroundStyle(OpenCodeColors.textStrong)
                        OpenCodeIconImage(icon: .chevronRight, size: schemeIconSize)
                            .rotationEffect(.degrees(90))
                    }
                    .padding(.leading, fieldStartPadding)
                    .padding(.trailing, schemeEndPadding)
                    .frame(maxHeight: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(ServerAddressSchemeButtonStyle())
                .disabled(!enabled)
                .accessibilityLabel("Scheme \(scheme.label)")
                .accessibilityHint("Opens scheme picker")

                TextField(hint.resolved, text: hostBinding)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .focused($focused)
                    .submitLabel(submitAction == .next ? .next : .done)
                    .onSubmit { onSubmit?() }
                    .padding(.leading, hostStartPadding)
                    .padding(.trailing, fieldEndPadding)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                    .disabled(!enabled)
                    .accessibilityLabel(
                        OpenCodeTextInput.resolveAccessibilityLabel(
                            hint: hint.resolved,
                            accessibilityLabel: accessibilityLabel?.resolved
                        )!
                    )
            }
            .frame(minHeight: minHeight, maxHeight: minHeight)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: cornerRadius))
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius)
                    .stroke(borderColor, lineWidth: focused || errorMessage != nil ? 2 : 1)
            }
            .opacity(enabled ? 1 : 0.6)

            if let errorMessage {
                Text(openCode: errorMessage)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.error)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear(perform: syncFromValue)
        .onChange(of: value) { _, _ in syncFromValue() }
        .onChange(of: focused) { _, newValue in
            isFocused?.wrappedValue = newValue
        }
        .onChange(of: isFocused?.wrappedValue) { _, newValue in
            if let newValue, focused != newValue {
                focused = newValue
            }
        }
        .openCodeBottomSheet(isPresented: $showSchemeSheet, title: "Scheme") {
            ForEach(ServerAddressScheme.allCases, id: \.self) { option in
                OpenCodeBottomSheetEntry(
                    title: .verbatim(option.label),
                    action: {
                        scheme = option
                        emit()
                        showSchemeSheet = false
                    }
                )
            }
        }
    }

    private var hostBinding: Binding<String> {
        Binding(
            get: { hostPort },
            set: { newValue in
                let applied = ServerAddressInputParsing.applyHostEdit(newValue, currentScheme: scheme)
                scheme = applied.scheme
                hostPort = applied.hostPort
                emit()
            }
        )
    }

    private var borderColor: Color {
        if errorMessage != nil {
            OpenCodeColors.error
        } else if focused {
            OpenCodeColors.interactive
        } else {
            OpenCodeColors.borderWeakerBase
        }
    }

    private func syncFromValue() {
        let parsed = ServerAddressInputParsing.parse(value: value, fallbackScheme: scheme)
        if parsed.scheme != scheme {
            scheme = parsed.scheme
        }
        if parsed.hostPort != hostPort {
            hostPort = parsed.hostPort
        }
    }

    private func emit() {
        let next = ServerAddressParts(scheme: scheme, hostPort: hostPort).toBaseURL()
        if next != value {
            value = next
        }
    }
}

private struct ServerAddressSchemeButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                if configuration.isPressed {
                    OpenCodeColors.borderWeakerBase.opacity(0.35)
                }
            }
    }
}

#Preview("Server Address HTTP") {
    VStack(spacing: 12) {
        OpenCodeServerAddressInput(value: .constant("http://127.0.0.1:4096/"))
        OpenCodeTextInput(text: .constant("opencode"), hint: "Username")
    }
    .padding()
    .openCodeTheme()
}

#Preview("Server Address HTTPS") {
    OpenCodeServerAddressInput(value: .constant("https://macbook.local:8080/"))
        .padding()
        .openCodeTheme()
}
