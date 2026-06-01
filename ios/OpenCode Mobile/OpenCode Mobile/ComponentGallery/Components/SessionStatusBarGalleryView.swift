import SwiftUI

struct SessionStatusBarGalleryView: View {
    @State private var connectionStatus: OpenCodeSessionConnectionStatus = .live
    @State private var contextAvailable = true
    @State private var contextUsagePercent = 42.0

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
                    VStack(alignment: .leading, spacing: 8) {
                        Text("\(Int(contextUsagePercent))%")
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textBase)
                        Slider(
                            value: $contextUsagePercent,
                            in: 0...100,
                            step: 1
                        )
                        .disabled(!contextAvailable)
                    }
                }
            }
            .padding(.horizontal, 16)

            OpenCodeSessionStatusBar(
                sessionID: "ses_01HZX9TvS3EfE4",
                connectionStatus: connectionStatus,
                contextUsageFraction: contextAvailable ? contextUsagePercent / 100 : nil,
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
