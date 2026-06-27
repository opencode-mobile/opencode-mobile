import SwiftUI

struct LiveUpdatesConnectionBanner: View {
    let state: EventStreamConnectionState

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Circle()
                .fill(state.homeStatusTint)
                .frame(width: 8, height: 8)
                .padding(.top, 4)
            VStack(alignment: .leading, spacing: 2) {
                Text(state.homeStatusLabel)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(state.homeStatusTint)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .accessibilityIdentifier("liveUpdatesConnectionBanner")
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        return state.homeStatusLabel
    }
}

#if DEBUG
#Preview {
    VStack(spacing: 16) {
        LiveUpdatesConnectionBanner(state: .connected)
        LiveUpdatesConnectionBanner(state: .connecting)
        LiveUpdatesConnectionBanner(state: .idle)
    }
    .openCodeTheme()
}
#endif
