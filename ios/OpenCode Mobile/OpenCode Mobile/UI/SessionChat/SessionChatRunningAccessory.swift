import SwiftUI

/// Feature-local Working/Retrying/Compacting chrome above the composer.
struct SessionChatRunningAccessory: View {
    let label: String

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            SessionChatRunningStatusDot()
            Text(label)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textWeak)
            Spacer(minLength: 0)
        }
        .padding(.leading, 20)
        .padding(.trailing, 16)
        .padding(.top, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }
}

private struct SessionChatRunningStatusDot: View {
    @State private var pulse = false

    var body: some View {
        Circle()
            .fill(OpenCodeColors.textWeak)
            .frame(width: 6, height: 6)
            .opacity(pulse ? 0.35 : 1)
            .animation(
                .easeInOut(duration: 0.9).repeatForever(autoreverses: true),
                value: pulse
            )
            .onAppear { pulse = true }
            .accessibilityHidden(true)
    }
}
