import SwiftUI

struct TerminalControlStrip: View {
    let onSend: (String) -> Void

    /// The Ctrl-C key cap is a keyboard symbol and stays as is; its spoken label is translated.
    private static let ctrlCCap = "^C"

    var body: some View {
        HStack(spacing: 6) {
            OpenCodeButton(
                variant: .compactText(title: .verbatim(Self.ctrlCCap)),
                contrast: .destructive,
                action: { onSend("\u{0003}") }
            )
            .accessibilityLabel(openCode: "terminal.key.ctrl_c")
            OpenCodeButton(
                variant: .compactText(title: "terminal.key.esc"),
                contrast: .low,
                action: { onSend("\u{001b}") }
            )
            OpenCodeButton(
                variant: .compactText(title: "terminal.key.tab"),
                contrast: .low,
                action: { onSend("\t") }
            )
            Spacer(minLength: 0)
            OpenCodeButton(
                variant: .iconSquare(.arrowUpward, accessibilityLabel: "terminal.key.up"),
                contrast: .low,
                action: { onSend("\u{001b}[A") }
            )
            OpenCodeButton(
                variant: .iconSquare(.arrowDownward, accessibilityLabel: "terminal.key.down"),
                contrast: .low,
                action: { onSend("\u{001b}[B") }
            )
            OpenCodeButton(
                variant: .iconSquare(.arrowBack, accessibilityLabel: "terminal.key.left"),
                contrast: .low,
                action: { onSend("\u{001b}[D") }
            )
            OpenCodeButton(
                variant: .iconSquare(.arrowForward, accessibilityLabel: "terminal.key.right"),
                contrast: .low,
                action: { onSend("\u{001b}[C") }
            )
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
    }
}
