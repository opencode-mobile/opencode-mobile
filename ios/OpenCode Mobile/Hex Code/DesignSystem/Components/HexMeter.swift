import SwiftUI

/// The live level meter: thin bars that scroll left as the level comes in. `level` is the bar
/// height for now, 0…1 (see `HexMeterLevel.height(dBFS:)`); the meter rises fast and falls slowly
/// on it, and bars at 0 rest as dots.
struct HexMeter: View {
    let level: () -> Double
    var bars = 36

    @State private var history: [CGFloat] = []
    @State private var envelope: Double = 0
    @State private var lastFrame: Date?

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30)) { context in
            Canvas { gc, size in
                let values = history.count == bars ? history : Array(repeating: 0, count: bars)
                let w = size.width / CGFloat(bars)
                let dot = min(3, w * 0.4)
                for (i, v) in values.enumerated() {
                    let h = max(dot, v * CGFloat(HexMeterLevel.variation(bar: i)) * size.height)
                    let rect = CGRect(x: CGFloat(i) * w + w * 0.3, y: (size.height - h) / 2, width: w * 0.4, height: h)
                    gc.fill(Path(roundedRect: rect, cornerRadius: w * 0.2), with: .style(HexColor.text1))
                }
            }
            .onChange(of: context.date) { _, now in
                let elapsed = lastFrame.map { now.timeIntervalSince($0) } ?? 1.0 / 30
                lastFrame = now
                envelope = HexMeterLevel.follow(envelope, towards: min(1, max(0, level())), elapsed: elapsed)
                var next = history.count == bars ? history : Array(repeating: 0, count: bars)
                next.removeFirst()
                next.append(CGFloat(envelope))
                history = next
            }
        }
        .accessibilityHidden(true)
    }
}
