import SwiftUI

enum OpenCodeKeyboardHintTone {
    /// How to use the keyboard here ("Press Tab to answer with the keyboard").
    case neutral
    /// A key press that will do something on the next press ("Esc again to interrupt").
    case warning
}

/// One line of caption text about a hardware-keyboard key, shown only while it
/// applies. Same component and tones on Android (`OpenCodeKeyboardHint`).
struct OpenCodeKeyboardHint: View {
    let text: String
    var tone: OpenCodeKeyboardHintTone = .neutral

    init(_ text: String, tone: OpenCodeKeyboardHintTone = .neutral) {
        precondition(!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        self.text = text
        self.tone = tone
    }

    var body: some View {
        Text(text)
            .font(OpenCodeTypography.caption)
            .foregroundStyle(foreground)
            .lineLimit(1)
            .padding(.horizontal, Self.horizontalPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var foreground: Color {
        switch tone {
        case .neutral: OpenCodeColors.textWeak
        case .warning: OpenCodeColors.warning
        }
    }

    /// Lines the text up with prompt and composer content.
    private static let horizontalPadding: CGFloat = 4
}

#Preview("Keyboard Hint") {
    VStack(alignment: .leading, spacing: 12) {
        OpenCodeKeyboardHint(OpenCodePromptKeyboardHint.text)
        OpenCodeKeyboardHint(OpenCodeInterruptKeyboardHint.text, tone: .warning)
    }
    .padding()
    .openCodeTheme()
}
