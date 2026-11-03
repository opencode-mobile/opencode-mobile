import SwiftUI

enum OpenCodeSessionConnectionStatus: String, Equatable, Sendable, CaseIterable {
    case live
    case connecting
    case offline
    case authFailed = "Authentication failed"
}

struct OpenCodeConnectionStatusLabel: View {
    let status: OpenCodeSessionConnectionStatus

    var body: some View {
        HStack(alignment: .center, spacing: 4) {
            Circle()
                .fill(status.dotColor)
                .frame(width: 6, height: 6)
            Text(status.label)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(status.accessibilityLabel))
        .accessibilityIdentifier("liveUpdatesStatus")
    }
}

extension OpenCodeSessionConnectionStatus {
    /// The visible status word.
    var label: LocalizedStringResource {
        switch self {
        case .live: LocalizedStringResource("live_updates.status.live", defaultValue: "live")
        case .connecting: LocalizedStringResource("live_updates.status.connecting", defaultValue: "connecting")
        case .offline: LocalizedStringResource("live_updates.status.offline", defaultValue: "offline")
        case .authFailed: LocalizedStringResource("live_updates.status.auth_failed", defaultValue: "Authentication failed")
        }
    }

    /// "Live updates <status>", read by VoiceOver.
    var accessibilityLabel: LocalizedStringResource {
        let status = String(localized: label)
        return LocalizedStringResource("live_updates.accessibility_label", defaultValue: "Live updates \(status)")
    }
}

private extension OpenCodeSessionConnectionStatus {
    var dotColor: Color {
        switch self {
        case .live:
            OpenCodeColors.success
        case .connecting:
            OpenCodeColors.warning
        case .offline, .authFailed:
            OpenCodeColors.error
        }
    }
}

#Preview("Connection status") {
    VStack(alignment: .leading, spacing: 8) {
        ForEach(OpenCodeSessionConnectionStatus.allCases, id: \.self) { status in
            OpenCodeConnectionStatusLabel(status: status)
        }
    }
    .padding(16)
    .openCodeTheme()
}
