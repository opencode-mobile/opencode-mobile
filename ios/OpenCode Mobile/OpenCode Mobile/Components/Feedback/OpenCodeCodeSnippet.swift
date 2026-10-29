import SwiftUI

enum OpenCodeCodeSnippetSurface {
    /// Raised fill for a snippet sitting on the page background.
    case raised
    /// Base fill for a snippet sitting on a raised card.
    case base
    /// Code surface of a syntax preset, given by its resolved syntax tokens: the editor
    /// background when the preset has one, else the base fill.
    case syntaxPreset([String: String])
}

/// Monospace command or code block. Syntax colors stay in an attributed string.
struct OpenCodeCodeSnippet: View {
    private let text: Text
    private let appliesForeground: Bool
    var surface: OpenCodeCodeSnippetSurface

    init(_ code: String, surface: OpenCodeCodeSnippetSurface = .raised) {
        precondition(!code.isEmpty)
        self.text = Text(code)
        self.appliesForeground = true
        self.surface = surface
    }

    init(_ code: AttributedString, surface: OpenCodeCodeSnippetSurface = .base) {
        precondition(!String(code.characters).isEmpty)
        self.text = Text(code)
        self.appliesForeground = false
        self.surface = surface
    }

    var body: some View {
        text
            .font(OpenCodeTypography.code)
            .modifier(CodeSnippetForeground(applies: appliesForeground))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Self.paddingHorizontal)
            .padding(.vertical, Self.paddingVertical)
            .background(fill, in: RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous))
    }

    private var fill: Color {
        switch surface {
        case .raised:
            OpenCodeColors.surfaceRaisedBase
        case .base:
            OpenCodeColors.surfaceBase
        case .syntaxPreset(let tokens):
            OpenCodeCodeColors.backgroundHex(in: tokens).map(Color.init(hex:)) ?? OpenCodeColors.surfaceBase
        }
    }

    private static let paddingHorizontal: CGFloat = 12
    private static let paddingVertical: CGFloat = 10
    private static let cornerRadius: CGFloat = 10
}

private struct CodeSnippetForeground: ViewModifier {
    let applies: Bool

    func body(content: Content) -> some View {
        if applies {
            content.foregroundStyle(OpenCodeColors.textStrong)
        } else {
            content
        }
    }
}

#Preview("Code Snippet") {
    VStack(alignment: .leading, spacing: 12) {
        OpenCodeCodeSnippet("opencode serve")
        OpenCodeCodeSnippet("opencode2 pair", surface: .base)
    }
    .padding()
    .openCodeTheme()
}
