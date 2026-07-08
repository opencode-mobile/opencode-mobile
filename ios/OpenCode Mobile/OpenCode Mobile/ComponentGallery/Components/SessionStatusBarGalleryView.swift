import SwiftUI

struct SessionStatusBarGalleryView: View {
    @State private var contextAvailable = true
    @State private var contextUsagePercent = 50

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Session status bar")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)

                ControlGroup(title: "Context") {
                    HStack(spacing: 8) {
                        OpenCodeChoiceBadge(
                            label: "Available",
                            selected: contextAvailable,
                            action: { contextAvailable = true }
                        )
                        OpenCodeChoiceBadge(
                            label: "Unavailable",
                            selected: !contextAvailable,
                            action: { contextAvailable = false }
                        )
                    }
                }

                ControlGroup(title: "Context usage") {
                    HStack(spacing: 8) {
                        ForEach([0, 50, 70, 85], id: \.self) { percent in
                            OpenCodeChoiceBadge(
                                label: "\(percent)%",
                                selected: contextUsagePercent == percent,
                                enabled: contextAvailable,
                                action: { contextUsagePercent = percent }
                            )
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            OpenCodeSessionStatusBar(
                contextUsageFraction: contextAvailable ? Double(contextUsagePercent) / 100 : nil,
                onContextClick: {}
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ControlGroup<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)
            content()
        }
    }
}

#Preview {
    SessionStatusBarGalleryView()
        .padding(16)
        .openCodeTheme()
}
