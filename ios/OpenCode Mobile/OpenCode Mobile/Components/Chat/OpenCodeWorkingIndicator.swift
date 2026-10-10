import SwiftUI

/// Shared activity state for the working row and the reasoning row.
enum OpenCodeChatActivityState {
    /// Pulsing dot. The working row shows its label. Reasoning says "Thinking".
    case working
    /// Check mark. The working row shows its label.
    case idle
    /// Row height collapses to zero.
    case collapsed
    /// Visible settled row. Reasoning says "Thought" or "Thought · Ns".
    case expanded
}

/// Six-point circle that pulses while a turn is in progress.
struct OpenCodePulsingDot: View {
    @State private var pulse = false

    var body: some View {
        Circle()
            .fill(OpenCodeColors.textWeak)
            .frame(width: Self.diameter, height: Self.diameter)
            .opacity(pulse ? 0.35 : 1)
            .animation(
                .easeInOut(duration: 0.9).repeatForever(autoreverses: true),
                value: pulse
            )
            .onAppear { pulse = true }
            .accessibilityHidden(true)
    }

    private static let diameter: CGFloat = 6
}

/// Working, retrying, or compacting status row. Idle swaps the dot for a check.
struct OpenCodeWorkingIndicator: View {
    let label: String
    var state: OpenCodeChatActivityState
    var fading: Bool = false

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            OpenCodeWorkingStatusMark(isIdle: markIsIdle)
            Text(label)
                .font(OpenCodeTypography.body)
                .foregroundStyle(OpenCodeColors.textWeak)
            Spacer(minLength: 0)
        }
        .opacity(collapsed ? 0 : (fading ? 0.35 : 1))
        .padding(.leading, collapsed ? 0 : 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Hosted cells with minSize 0 treated `frame(height: nil)` as 0 and
        // never painted Working / Idle. Fixed expanded height matches the
        // list estimate so pin-to-end needs no estimate→real correction.
        .frame(height: collapsed ? 0 : WorkingTurnIndicator.rowHeight)
        .clipped()
        .accessibilityElement(children: .ignore)
        .accessibilityHidden(collapsed)
        .accessibilityLabel(label)
        .transaction { transaction in
            // Host clears animations so cells do not animate on open. Re-enable
            // Idle so the lamp can become a check, then dismiss.
            transaction.animation = animatesIdle ? .easeInOut(duration: 0.22) : nil
        }
    }

    private var collapsed: Bool { state == .collapsed }

    private var markIsIdle: Bool {
        switch state {
        case .idle:
            true
        case .collapsed:
            WorkingTurnIndicator.showsIdleMark(label: label)
        case .working, .expanded:
            false
        }
    }

    private var animatesIdle: Bool {
        markIsIdle || WorkingTurnIndicator.showsIdleMark(label: label)
    }
}

private struct OpenCodeWorkingStatusMark: View {
    var isIdle: Bool
    var body: some View {
        ZStack {
            OpenCodePulsingDot()
                .opacity(isIdle ? 0 : 1)
            OpenCodeIconImage(icon: .check, size: 12)
                .foregroundStyle(OpenCodeColors.textWeak)
                .opacity(isIdle ? 1 : 0)
                .scaleEffect(isIdle ? 1 : 0.4)
        }
        .frame(width: 12, height: 12)
        .accessibilityHidden(true)
    }
}

#Preview("Working Indicator") {
    VStack(alignment: .leading, spacing: 12) {
        OpenCodeWorkingIndicator(label: "Working", state: .working)
        OpenCodeWorkingIndicator(label: "Idle", state: .idle)
        OpenCodeWorkingIndicator(label: "Working", state: .expanded)
    }
    .padding()
    .openCodeTheme()
}
