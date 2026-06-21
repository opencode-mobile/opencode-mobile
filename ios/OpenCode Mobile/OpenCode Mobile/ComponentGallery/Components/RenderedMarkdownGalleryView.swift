import SwiftUI

private enum RenderedMarkdownSample: String, CaseIterable {
    case assistant = "Assistant"
    case outgoing = "Outgoing"
    case diff = "Diff"

    var variant: OpenCodeRenderedMarkdownVariant {
        switch self {
        case .assistant:
            .assistantMessage
        case .outgoing:
            .outgoingMessage
        case .diff:
            .standalone
        }
    }

    var markdown: String {
        switch self {
        case .assistant:
            """
            ### Plan

            - Keep the chat native.
            - Use the renderer only for formatted text.

            ```swift
            let renderer = "native SwiftUI"
            ```
            """
        case .outgoing:
            """
            Please compare `main` against my branch and summarize:

            | Area | Need |
            | --- | --- |
            | Tests | focused |
            | Risk | low |
            """
        case .diff:
            """
            ```diff
            @@ SessionChatView.swift
            -Text(message.text)
            +OpenCodeRenderedMarkdown(markdown: message.text)
            ```
            """
        }
    }
}

struct RenderedMarkdownGalleryView: View {
    @State private var sample: RenderedMarkdownSample = .assistant

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Picker("Sample", selection: $sample) {
                ForEach(RenderedMarkdownSample.allCases, id: \.self) { option in
                    Text(option.rawValue).tag(option)
                }
            }
            .pickerStyle(.segmented)

            OpenCodeRenderedMarkdown(
                markdown: sample.markdown,
                variant: sample.variant
            )
            .padding(12)
            .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
            )
        }
    }
}

#Preview {
    RenderedMarkdownGalleryView()
        .padding()
        .openCodeTheme()
}
