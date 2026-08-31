import SwiftUI

struct TerminalControlStrip: View {
    let onSend: (String) -> Void

    var body: some View {
        HStack(spacing: 6) {
            OpenCodeButton(
                variant: .compactText(title: "Ctrl-C"),
                contrast: .destructive,
                action: { onSend("\u{0003}") }
            )
            OpenCodeButton(
                variant: .compactText(title: "Esc"),
                contrast: .low,
                action: { onSend("\u{001b}") }
            )
            OpenCodeButton(
                variant: .compactText(title: "Tab"),
                contrast: .low,
                action: { onSend("\t") }
            )
            Spacer(minLength: 0)
            OpenCodeButton(
                variant: .iconSquare(.arrowUpward, accessibilityLabel: "Up"),
                contrast: .low,
                action: { onSend("\u{001b}[A") }
            )
            OpenCodeButton(
                variant: .iconSquare(.arrowDownward, accessibilityLabel: "Down"),
                contrast: .low,
                action: { onSend("\u{001b}[B") }
            )
            OpenCodeButton(
                variant: .iconSquare(.arrowBack, accessibilityLabel: "Left"),
                contrast: .low,
                action: { onSend("\u{001b}[D") }
            )
            OpenCodeButton(
                variant: .iconSquare(.chevronRight, accessibilityLabel: "Right"),
                contrast: .low,
                action: { onSend("\u{001b}[C") }
            )
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
    }
}
