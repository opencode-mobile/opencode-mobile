import SwiftUI

struct SessionStatusBarGalleryView: View {
    @State private var connectionStatus: OpenCodeSessionConnectionStatus = .live
    @State private var contextAvailable = true

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Session status bar")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textPrimary)

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
            }
            .padding(.horizontal, 16)

            OpenCodeSessionStatusBar(
                sessionID: "ses_01HZX9TvS3EfE4",
                connectionStatus: connectionStatus,
                contextUsageFraction: contextAvailable ? 0.42 : nil,
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
                .foregroundStyle(OpenCodeColors.textPrimary)
            content()
        }
    }
}

#Preview {
    SessionStatusBarGalleryView()
        .padding(16)
        .openCodeTheme()
}
