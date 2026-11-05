import SwiftUI

/// The live level meter: thin bars that scroll left as the level comes in.
struct HexMeter: View {
    let level: () -> Double
    var bars = 36

    @State private var history: [CGFloat] = []

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30)) { context in
            Canvas { gc, size in
                let values = history.count == bars ? history : Array(repeating: 0, count: bars)
                let w = size.width / CGFloat(bars)
                for (i, v) in values.enumerated() {
                    let h = max(3, min(1, v * 1.6) * size.height * 0.6)
                    let rect = CGRect(x: CGFloat(i) * w + w * 0.3, y: (size.height - h) / 2, width: w * 0.4, height: h)
                    gc.fill(Path(roundedRect: rect, cornerRadius: w * 0.2), with: .style(HexColor.text1))
                }
            }
            .onChange(of: context.date) {
                var next = history.count == bars ? history : Array(repeating: 0, count: bars)
                next.removeFirst()
                next.append(CGFloat(level()))
                history = next
            }
        }
        .accessibilityHidden(true)
    }
}
