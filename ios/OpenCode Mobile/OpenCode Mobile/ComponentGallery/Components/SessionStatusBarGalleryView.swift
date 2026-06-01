import SwiftUI

struct SessionStatusBarGalleryView: View {
    @State private var connectionStatus: OpenCodeSessionConnectionStatus = .live
    @State private var contextAvailable = true
    @State private var contextUsagePercent = 50

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Session status bar")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)

                ControlGroup(title: "Connection") {
                    HStack(spacing: 8) {
                        ForEach(OpenCodeSessionConnectionStatus.allCases, id: \.self) { status in
                            OpenCodeChoiceBadge(
                                label: status.rawValue,
                                selected: connectionStatus == status,
                                action: { connectionStatus = status }
                            )
                        }
                    }
                }

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
                sessionID: "ses_01HZX9TvS3EfE4",
                connectionStatus: connectionStatus,
                contextUsageFraction: contextAvailable ? Double(contextUsagePercent) / 100 : nil,
                onCopySessionID: {},
                onConnectionClick: {},
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
