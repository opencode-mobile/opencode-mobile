import SwiftUI

/// The prototype's spinner: a faint ring with a quarter arc going round. By default the ring is
/// `text4` and the arc `solid`; with a colour, both take it (the ring at 25 %).
struct HexSpinner: View {
    var size: CGFloat = HexIconSize.regular
    var color: Color?
    /// One turn, in seconds.
    var period: TimeInterval = 0.9

    @State private var turning = false

    var body: some View {
        ZStack {
            Circle()
                .stroke(track, lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: 0.25)
                .stroke(color ?? HexColor.solid, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(turning ? 360 : 0))
        }
        .padding(lineWidth / 2 + size / 16)
        .frame(width: size, height: size)
        .onAppear {
            withAnimation(.linear(duration: period).repeatForever(autoreverses: false)) { turning = true }
        }
        .accessibilityHidden(true)
    }

    private var lineWidth: CGFloat { size * 1.5 / 16 }

    private var track: Color {
        if let color { return color.opacity(0.25) }
        return HexColor.text4
    }
}
