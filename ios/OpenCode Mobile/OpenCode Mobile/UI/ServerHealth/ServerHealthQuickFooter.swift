import SwiftUI

/// Footer of the active profile's card on Servers: status, ping, recent checks, and a link
/// to Server Health. Tapping it opens Server Health.
struct ServerHealthQuickFooter: View {
    let quickView: ServerHealthQuickView
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .center) {
                    ServerHealthStatusChip(state: quickView.state)
                    Spacer(minLength: 8)
                    HStack(alignment: .firstTextBaseline, spacing: 3) {
                        Text(verbatim: quickView.currentPingMs.map(String.init) ?? ServerHealthText.placeholder)
                            .font(OpenCodeTypography.title.monospacedDigit())
                            .foregroundStyle(OpenCodeColors.textStrong)
                        Text(verbatim: "ms")
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeak)
                    }
                }
                OpenCodeSparkline(
                    values: quickView.recentPings.map { $0.map(Double.init) },
                    tone: quickView.state == .live ? .success : .warning,
                    showsGaps: true
                )
                .frame(height: 40)
                HStack(spacing: 8) {
                    Text(openCode: summary)
                        .font(OpenCodeTypography.caption.monospacedDigit())
                        .foregroundStyle(OpenCodeColors.textBase)
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                    Spacer(minLength: 8)
                    HStack(spacing: 2) {
                        Text("server_health.details")
                        OpenCodeIconImage(icon: .chevronRight, size: 14)
                    }
                    .font(OpenCodeTypography.caption.weight(.medium))
                    .foregroundStyle(OpenCodeColors.textInteractiveBase)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("server_health.open_details"))
        .accessibilityValue(Text(verbatim: "\(ServerHealthText.status(quickView.state).resolved), \(summary.resolved)"))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("serverHealthQuickView")
    }

    private var summary: OpenCodeText {
        guard let uptime = quickView.uptimePercent else {
            return OpenCodeText(LocalizedStringResource("server_health.quick_view_measuring", defaultValue: "Measuring…"))
        }
        return OpenCodeText(LocalizedStringResource(
            "server_health.quick_view_summary",
            defaultValue: "Uptime \(ServerHealthFormat.percent(uptime)) · \(quickView.reconnects) reconnects today"
        ))
    }
}
