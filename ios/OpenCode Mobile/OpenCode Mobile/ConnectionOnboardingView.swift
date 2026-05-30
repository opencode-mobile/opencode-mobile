import SwiftUI

struct ConnectionOnboardingView: View {
    let onConnectClick: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            VStack(spacing: 12) {
                Text("Connect to your server")
                    .font(OpenCodeTypography.heading)
                    .multilineTextAlignment(.center)
                Text("OpenCode Mobile controls sessions on an opencode server running on your development machine.")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textSecondary)
                    .multilineTextAlignment(.center)
            }
            OpenCodeButton(variant: .fullWidthText(title: "Connect to server"), action: onConnectClick)
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundPrimary)
    }
}
