import SwiftUI

struct ConnectionOnboardingView: View {
    @Binding var showSetupSheet: Bool

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
            OpenCodeButton(variant: .fullWidthText(title: "Connect to server")) {
                showSetupSheet = true
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundPrimary)
    }
}
