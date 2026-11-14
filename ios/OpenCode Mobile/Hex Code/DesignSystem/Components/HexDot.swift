import SwiftUI

/// A 6 pt status dot. Red and syncing pulse.
struct HexDot: View {
    enum Tone: Equatable, Sendable {
        case neutral
        case green
        case amber
        case red
        case syncing
    }

    var tone: Tone = .neutral
    var size: CGFloat = 6

    @State private var dimmed = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .opacity(dimmed ? 0.35 : 1)
            .onAppear { startPulse() }
            .onChange(of: tone) { startPulse() }
            .onChange(of: reduceMotion) { startPulse() }
            .accessibilityHidden(true)
    }

    private var color: Color {
        switch tone {
        case .neutral, .syncing: HexColor.text3
        case .green: HexColor.green
        case .amber: HexColor.amber
        case .red: HexColor.red
        }
    }

    /// The pulse's half period; nil for a steady dot. With Reduce Motion every dot is steady, at
    /// full colour.
    static func pulse(for tone: Tone, reduced: Bool) -> TimeInterval? {
        guard !reduced else { return nil }
        switch tone {
        case .red: return 0.6
        case .syncing: return 0.5
        default: return nil
        }
    }

    private func startPulse() {
        guard let pulse = Self.pulse(for: tone, reduced: reduceMotion) else {
            // Rest at the visible state, stopping a pulse already running.
            withAnimation(nil) { dimmed = false }
            return
        }
        withAnimation(.easeInOut(duration: pulse).repeatForever(autoreverses: true)) { dimmed = true }
    }
}
