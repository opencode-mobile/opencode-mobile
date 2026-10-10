import SwiftUI

struct WorkingIndicatorGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            labeled("Working") {
                OpenCodeWorkingIndicator(label: "Working", state: .working)
            }
            labeled("Idle") {
                OpenCodeWorkingIndicator(label: "Idle", state: .idle)
            }
            labeled("Expanded") {
                OpenCodeWorkingIndicator(label: "Retrying", state: .expanded)
            }
            labeled("Fading") {
                OpenCodeWorkingIndicator(label: "Idle", state: .idle, fading: true)
            }
            labeled("Collapsed") {
                OpenCodeWorkingIndicator(label: "Idle", state: .collapsed)
            }
            OpenCodePulsingDot()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func labeled(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
            content()
        }
    }
}

#Preview {
    WorkingIndicatorGalleryView()
        .padding()
        .openCodeTheme()
}
