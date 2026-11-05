import SwiftUI

/// First run, step 1: the one rule and how to connect.
struct WelcomeView: View {
    let onScan: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HexIconView(.mark, size: HexSize.mark)
                .foregroundStyle(HexColor.text1)
                .padding(.top, 40)
            HStack(spacing: 10) {
                Text(LocalizedStringResource("welcome.title", defaultValue: "Hex Code"))
                    .hexFont(.display)
                    .foregroundStyle(HexColor.text1)
                HexBadge(LocalizedStringResource("welcome.badge", defaultValue: "experiment"))
            }
            .padding(.top, 22)
            Text(LocalizedStringResource("welcome.tagline", defaultValue: "Talk to the coding agent on your computer, from your phone."))
                .hexFont(.body)
                .foregroundStyle(HexColor.text2)
                .padding(.top, 10)
            VStack(alignment: .leading, spacing: 6) {
                Text(LocalizedStringResource("welcome.rule.title", defaultValue: "There are no rules."))
                    .hexFont(.pageTitle)
                    .foregroundStyle(HexColor.text1)
                Text(LocalizedStringResource("welcome.rule.body", defaultValue: "Talk to it however you like. Try things, see where it goes."))
                    .hexFont(.body)
                    .foregroundStyle(HexColor.text2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 16)
            .overlay(alignment: .top) { HexColor.line.frame(height: 1) }
            .padding(.top, 30)

            Spacer(minLength: 24)

            howToConnect
                .padding(.bottom, 16)
            HexPrimaryButton(LocalizedStringResource("welcome.scan", defaultValue: "Scan the code"), action: onScan)
            Text(LocalizedStringResource("welcome.footer", defaultValue: "Needs OpenCode 2.2.5+"))
                .hexFont(.footnote)
                .foregroundStyle(HexColor.text3)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .padding(.top, 10)
                .padding(.bottom, 6)
        }
        .padding(.horizontal, HexSpacing.screen)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .hexScreenBackground()
    }

    private var howToConnect: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(LocalizedStringResource("welcome.connect.title", defaultValue: "To connect"))
                .hexFont(HexTextStyle.caption.weight(.medium))
                .foregroundStyle(HexColor.text3)
                .padding(.bottom, 10)
            step(1) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(LocalizedStringResource("welcome.connect.step1", defaultValue: "On your computer, run"))
                    CommandChip(command: "opencode pair")
                }
            }
            step(2) {
                Text(LocalizedStringResource("welcome.connect.step2", defaultValue: "Scan the code it shows"))
            }
            Text(LocalizedStringResource(
                "welcome.connect.remote",
                defaultValue: "Not on the same network? `opencode pair --remote` connects through the internet."
            ))
            .hexFont(.caption)
            .foregroundStyle(HexColor.text3)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 6)
            .padding(.leading, 34)
        }
    }

    private func step<Content: View>(_ number: Int, @ViewBuilder content: () -> Content) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(verbatim: "\(number)")
                .hexFont(.mono)
                .foregroundStyle(HexColor.text2)
                .frame(width: 22, height: 22)
                .overlay(Circle().strokeBorder(HexColor.line2, lineWidth: 1))
            content()
                .hexFont(.body)
                .foregroundStyle(HexColor.text1)
        }
        .padding(.vertical, 7)
    }
}

/// A command to type on the computer, as in "run `opencode pair`".
private struct CommandChip: View {
    let command: String

    var body: some View {
        Text(verbatim: command)
            .hexFont(HexTextStyle.small.weight(.regular).monospaced())
            .foregroundStyle(HexColor.text1)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(HexColor.panel2, in: RoundedRectangle(cornerRadius: HexRadius.s, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: HexRadius.s, style: .continuous)
                    .strokeBorder(HexColor.line, lineWidth: 1)
            )
            .textSelection(.enabled)
    }
}
