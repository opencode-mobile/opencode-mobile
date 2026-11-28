import SwiftUI

/// App text and tones for Server Health values, shared by the screen and the Servers card.
enum ServerHealthText {
    static var placeholder: String {
        String(localized: "server_health.unknown_value", defaultValue: "—")
    }

    /// "API v1" or "API v2".
    static func protocolLabel(_ serverProtocol: ServerProtocol?) -> String? {
        switch serverProtocol {
        case .v1: String(localized: "server_health.protocol_v1", defaultValue: "API v1")
        case .v2: String(localized: "server_health.protocol_v2", defaultValue: "API v2")
        case nil: nil
        }
    }

    static func status(_ state: ServerHealthConnectionState) -> OpenCodeText {
        switch state {
        case .live: OpenCodeText(LocalizedStringResource("server_health.status_live", defaultValue: "Live"))
        case .connecting: OpenCodeText(LocalizedStringResource("server_health.status_connecting", defaultValue: "Connecting"))
        case .reconnecting: OpenCodeText(LocalizedStringResource("server_health.status_reconnecting", defaultValue: "Reconnecting"))
        case .offline: OpenCodeText(LocalizedStringResource("server_health.status_offline", defaultValue: "Offline"))
        case .authFailed: OpenCodeText(LocalizedStringResource("server_health.status_auth_failed", defaultValue: "Auth failed"))
        case .stopped: OpenCodeText(LocalizedStringResource("server_health.status_stopped", defaultValue: "Not measuring"))
        }
    }

    static func statusTone(_ state: ServerHealthConnectionState) -> OpenCodeListItemStatusTone {
        switch state {
        case .live: .success
        case .connecting: .interactive
        case .reconnecting: .warning
        case .offline, .authFailed: .error
        case .stopped: .stale
        }
    }

    static func category(_ category: ServerHealthFailureCategory) -> OpenCodeText {
        switch category {
        case .network: OpenCodeText(LocalizedStringResource("server_health.category_network", defaultValue: "Network"))
        case .timeout: OpenCodeText(LocalizedStringResource("server_health.category_timeout", defaultValue: "Timeout"))
        case .heartbeat: OpenCodeText(LocalizedStringResource("server_health.category_heartbeat", defaultValue: "Heartbeat stall"))
        case .server: OpenCodeText(LocalizedStringResource("server_health.category_server", defaultValue: "Server error"))
        case .auth: OpenCodeText(LocalizedStringResource("server_health.category_auth", defaultValue: "Authentication"))
        case .other: OpenCodeText(LocalizedStringResource("server_health.category_other", defaultValue: "Other"))
        }
    }

    static func categoryTone(_ category: ServerHealthFailureCategory) -> OpenCodeProportionTone {
        switch category {
        case .auth, .server: .error
        case .network, .timeout, .heartbeat: .warning
        case .other: .info
        }
    }

    static func milliseconds(_ value: Int?) -> String {
        value.map(ServerHealthFormat.milliseconds) ?? placeholder
    }

    static func percent(_ value: Double?) -> String {
        value.map { ServerHealthFormat.percent($0) } ?? placeholder
    }
}

/// Status chip: a dot (pulsing while live) and the state word, tinted by tone.
struct ServerHealthStatusChip: View {
    let state: ServerHealthConnectionState

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dimmed = false

    private var color: Color {
        switch ServerHealthText.statusTone(state) {
        case .success: OpenCodeColors.success
        case .interactive: OpenCodeColors.interactive
        case .warning: OpenCodeColors.warning
        case .error: OpenCodeColors.error
        case .info: OpenCodeColors.textBase
        case .stale: OpenCodeColors.textWeaker
        }
    }

    private var pulses: Bool { state == .live && !reduceMotion }

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(color)
                .frame(width: 7, height: 7)
                .opacity(pulses && dimmed ? 0.35 : 1)
            Text(openCode: ServerHealthText.status(state))
                .font(OpenCodeTypography.caption.weight(.semibold))
                .foregroundStyle(color)
                .lineLimit(1)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(color.opacity(state == .stopped ? 0.08 : 0.12), in: Capsule())
        .accessibilityElement(children: .combine)
        .onAppear { updatePulse() }
        .onChange(of: pulses) { _, _ in updatePulse() }
    }

    private func updatePulse() {
        guard pulses else {
            var still = Transaction()
            still.disablesAnimations = true
            withTransaction(still) { dimmed = false }
            return
        }
        withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) {
            dimmed = true
        }
    }
}
