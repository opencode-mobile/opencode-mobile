import SwiftUI

extension EventStreamConnectionState {
    var homeStatusLabel: String {
        switch self {
        case .idle:
            "Live updates off"
        case .connecting:
            "Connecting live updates…"
        case .connected:
            "Live updates"
        case .disconnected:
            "Live updates disconnected"
        case .reconnecting:
            "Reconnecting live updates…"
        }
    }

    var homeStatusTint: Color {
        switch self {
        case .connected:
            OpenCodeColors.success
        case .connecting, .reconnecting:
            OpenCodeColors.warning
        case .idle, .disconnected:
            OpenCodeColors.textBase
        }
    }
}
