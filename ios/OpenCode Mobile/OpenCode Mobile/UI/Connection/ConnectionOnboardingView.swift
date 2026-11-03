import SwiftUI

struct ConnectionOnboardingView: View {
    let onConnectClick: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            VStack(spacing: 12) {
                Text("connection.onboarding.title")
                    .font(OpenCodeTypography.heading)
                    .multilineTextAlignment(.center)
                Text("connection.onboarding.body")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
                    .multilineTextAlignment(.center)
            }
            OpenCodeButton(variant: .fullWidthText(title: "connection.onboarding.connect"), action: onConnectClick)
            Spacer()
        }
        .padding(24)
        .openCodeReadableColumn()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
    }
}
