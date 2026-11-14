import SwiftUI

/// Three rounded bars, the same shape as the app icon.
struct HexCodeMark: View {
    var body: some View {
        Canvas { context, size in
            let unit = size.width / 24
            let bars: [(x: CGFloat, top: CGFloat, bottom: CGFloat)] = [(8, 10, 14), (12, 7, 17), (16, 9, 15)]
            for bar in bars {
                var path = Path()
                path.move(to: CGPoint(x: bar.x * unit, y: bar.top * unit))
                path.addLine(to: CGPoint(x: bar.x * unit, y: bar.bottom * unit))
                context.stroke(path, with: .color(.white), style: StrokeStyle(lineWidth: 2.4 * unit, lineCap: .round))
            }
        }
    }
}
