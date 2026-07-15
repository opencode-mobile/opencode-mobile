import SwiftUI

struct TextInputGalleryView: View {
    @State private var text = "http://127.0.0.1:4096/"
    @State private var kind: OpenCodeTextInputKind = .url
    @State private var enabled = true
    @State private var showsError = false
    @State private var showsLabel = true

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 8) {
                ForEach(OpenCodeTextInputKind.allCases, id: \.self) { option in
                    OpenCodeChoiceBadge(label: label(option), selected: kind == option) {
                        kind = option
                        text = sampleValue(option)
                    }
                }
            }
            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Enabled", selected: enabled) { enabled.toggle() }
                OpenCodeChoiceBadge(label: "Error", selected: showsError) { showsError.toggle() }
                OpenCodeChoiceBadge(label: "Label", selected: showsLabel) { showsLabel.toggle() }
            }
            OpenCodeTextInput(
                label: showsLabel ? fieldLabel : nil,
                text: $text,
                placeholder: placeholder,
                accessibilityLabel: showsLabel ? nil : fieldLabel,
                kind: kind,
                enabled: enabled,
                errorMessage: showsError ? errorMessage : nil
            )
        }
    }

    private func label(_ kind: OpenCodeTextInputKind) -> String {
        switch kind {
        case .text: "Text"
        case .url: "URL"
        case .username: "Username"
        case .password: "Password"
        }
    }

    private var fieldLabel: String {
        switch kind {
        case .text: "Project name"
        case .url: "Server URL"
        case .username: "Username"
        case .password: "Password"
        }
    }

    private var placeholder: String {
        switch kind {
        case .text: "OpenCode Mobile"
        case .url: "http://127.0.0.1:4096/"
        case .username: "opencode"
        case .password: "Server password"
        }
    }

    private var errorMessage: String { "Enter a valid \(fieldLabel.lowercased())" }

    private func sampleValue(_ kind: OpenCodeTextInputKind) -> String {
        switch kind {
        case .text: "OpenCode Mobile"
        case .url: "http://127.0.0.1:4096/"
        case .username: "opencode"
        case .password: "correct horse battery staple"
        }
    }
}

#Preview { TextInputGalleryView().padding().openCodeTheme() }
