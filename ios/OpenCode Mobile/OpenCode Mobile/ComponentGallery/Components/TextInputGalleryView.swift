import SwiftUI

struct TextInputGalleryView: View {
    @State private var text = ""
    @State private var kind: OpenCodeTextInputKind = .url
    @State private var enabled = true
    @State private var showsError = false
    @State private var isEmpty = true
    @State private var multiline = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(OpenCodeTextInputKind.allCases, id: \.self) { option in
                        OpenCodeChoiceBadge(label: label(option), selected: kind == option) {
                            kind = option
                            if option != .text { multiline = false }
                            text = isEmpty ? "" : sampleValue(option)
                        }
                    }
                }
            }
            HStack(spacing: 8) {
                OpenCodeChoiceBadge(label: "Enabled", selected: enabled) { enabled.toggle() }
                OpenCodeChoiceBadge(label: "Error", selected: showsError) { showsError.toggle() }
                OpenCodeChoiceBadge(label: "Empty", selected: isEmpty) {
                    isEmpty.toggle()
                    text = isEmpty ? "" : sampleValue(kind)
                }
                OpenCodeChoiceBadge(label: "Multi-line", selected: multiline) {
                    multiline.toggle()
                    if multiline { kind = .text }
                }
            }
            OpenCodeTextInput(
                text: $text,
                hint: fieldHint,
                kind: kind,
                enabled: enabled,
                errorMessage: showsError ? errorMessage : nil,
                minLines: multiline ? 4 : 1,
                maxLines: multiline ? 8 : 1
            )
        }
    }

    private func label(_ kind: OpenCodeTextInputKind) -> String {
        switch kind {
        case .text: "Text"
        case .url: "URL"
        case .username: "Username"
        case .password: "Password"
        case .filter: "Filter"
        }
    }

    private var fieldHint: String {
        switch kind {
        case .text: "Project name"
        case .url: "Server URL"
        case .username: "Username"
        case .password: "Password"
        case .filter: "Filter models"
        }
    }

    private var errorMessage: String { "Enter a valid \(fieldHint.lowercased())" }

    private func sampleValue(_ kind: OpenCodeTextInputKind) -> String {
        switch kind {
        case .text: "OpenCode Mobile"
        case .url: "http://127.0.0.1:4096/"
        case .username: "opencode"
        case .password: "correct horse battery staple"
        case .filter: "claude"
        }
    }
}

#Preview { TextInputGalleryView().padding().openCodeTheme() }
