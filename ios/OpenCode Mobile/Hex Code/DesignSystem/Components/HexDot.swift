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

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .opacity(dimmed ? 0.35 : 1)
            .onAppear { startPulse() }
            .onChange(of: tone) { startPulse() }
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

    private var pulse: TimeInterval? {
        switch tone {
        case .red: 0.6
        case .syncing: 0.5
        default: nil
        }
    }

    private func startPulse() {
        guard let pulse else {
            dimmed = false
            return
        }
        withAnimation(.easeInOut(duration: pulse).repeatForever(autoreverses: true)) { dimmed = true }
    }
}
