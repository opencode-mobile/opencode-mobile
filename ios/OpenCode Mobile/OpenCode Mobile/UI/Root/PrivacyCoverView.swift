import SwiftUI

/// Opaque surface over the whole window while the app is not in the foreground or the app
/// lock is engaged (#755). Replaces the app-switcher snapshot and blocks touches underneath.
struct PrivacyCoverView: View {
    let locked: Bool
    let onUnlock: () -> Void

    var body: some View {
        ZStack {
            OpenCodeColors.backgroundBase.ignoresSafeArea()
            VStack(spacing: 16) {
                OpenCodeIconImage(icon: .lock, size: 40)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text("OpenCode Mobile")
                    .font(OpenCodeTypography.title)
                    .foregroundStyle(OpenCodeColors.textStrong)
                if locked {
                    OpenCodeButton(variant: .fullWidthText(title: "Unlock")) {
                        onUnlock()
                    }
                    .padding(.top, 8)
                }
            }
            .padding(.horizontal, 32)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .contain)
        .accessibilityLabel(locked ? "OpenCode Mobile is locked" : "OpenCode Mobile")
        .accessibilityIdentifier("privacyCover")
    }
}
