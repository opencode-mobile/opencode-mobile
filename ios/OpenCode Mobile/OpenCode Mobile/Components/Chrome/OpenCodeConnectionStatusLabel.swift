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
            Text(status.rawValue)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
                .lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Live updates \(status.rawValue)")
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
