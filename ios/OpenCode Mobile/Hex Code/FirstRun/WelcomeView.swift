import OpenCodeKit
import SwiftUI

/// First run, step 1: the one rule and how to connect.
struct WelcomeView: View {
    let onScan: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HexCodeMark()
                .frame(width: 52, height: 52)
                .background(Color.black, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .padding(.top, 40)
                .accessibilityHidden(true)
            HStack(spacing: 10) {
                Text(LocalizedStringResource("welcome.title", defaultValue: "Hex Code"))
                    .font(.system(size: 30, weight: .semibold))
                    .tracking(-0.9)
                    .foregroundStyle(OpenCodeColors.textStrong)
                OpenCodeTag("welcome.badge", size: .small)
            }
            .padding(.top, 22)
            Text(LocalizedStringResource("welcome.tagline", defaultValue: "Talk to the coding agent on your computer, from your phone."))
                .font(.system(size: 16))
                .foregroundStyle(OpenCodeColors.textWeak)
                .padding(.top, 10)
            VStack(alignment: .leading, spacing: 6) {
                Text(LocalizedStringResource("welcome.rule.title", defaultValue: "There are no rules."))
                    .font(.system(size: 21, weight: .semibold))
                    .tracking(-0.4)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text(LocalizedStringResource("welcome.rule.body", defaultValue: "Talk to it however you like. Try things, see where it goes."))
                    .font(.system(size: 15))
                    .lineSpacing(3)
                    .foregroundStyle(OpenCodeColors.textWeak)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 16)
            .overlay(alignment: .top) { Divider().overlay(OpenCodeColors.borderWeakBase) }
            .padding(.top, 30)

            Spacer(minLength: 24)

            howToConnect
                .padding(.bottom, 16)
            HexPrimaryButton(title: LocalizedStringResource("welcome.scan", defaultValue: "Scan the code"), action: onScan)
            Text(LocalizedStringResource("welcome.footer", defaultValue: "Needs OpenCode 2.2.5+"))
                .font(.system(size: 12))
                .foregroundStyle(OpenCodeColors.textWeaker)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .padding(.top, 10)
                .padding(.bottom, 6)
        }
        .padding(.horizontal, HexLayout.edge)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(OpenCodeColors.backgroundBase)
    }

    private var howToConnect: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(LocalizedStringResource("welcome.connect.title", defaultValue: "To connect"))
                .font(.system(size: 12.5, weight: .medium))
                .foregroundStyle(OpenCodeColors.textWeaker)
                .padding(.bottom, 10)
            step(1) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(LocalizedStringResource("welcome.connect.step1", defaultValue: "On your computer, run"))
                    HexCommand(command: "opencode pair")
                }
            }
            step(2) {
                Text(LocalizedStringResource("welcome.connect.step2", defaultValue: "Scan the code it shows"))
            }
            Text(LocalizedStringResource(
                "welcome.connect.remote",
                defaultValue: "Not on the same network? `opencode pair --remote` connects through the internet."
            ))
            .font(.system(size: 12.5))
            .foregroundStyle(OpenCodeColors.textWeaker)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 6)
            .padding(.leading, 34)
        }
    }

    private func step<Content: View>(_ number: Int, @ViewBuilder content: () -> Content) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(verbatim: "\(number)")
                .font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(OpenCodeColors.textWeak)
                .frame(width: 22, height: 22)
                .overlay(Circle().stroke(OpenCodeColors.borderWeakBase, lineWidth: 1))
            content()
                .font(.system(size: 15))
                .foregroundStyle(OpenCodeColors.textStrong)
        }
        .padding(.vertical, 7)
    }
}
