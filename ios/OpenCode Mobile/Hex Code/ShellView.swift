import SwiftUI

/// Placeholder first screen for the Hex Code shell: the mark, the name, and nothing else yet.
struct ShellView: View {
    var body: some View {
        VStack(spacing: 18) {
            HexCodeMark()
                .frame(width: 64, height: 64)
            VStack(spacing: 6) {
                Text("Hex Code")
                    .font(.system(size: 30, weight: .semibold))
                    .tracking(-0.6)
                Text("Empty shell. The app comes next.")
                    .font(.system(size: 15))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(red: 0.043, green: 0.047, blue: 0.055))
        .foregroundStyle(.white)
        .preferredColorScheme(.dark)
    }
}

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

#Preview {
    ShellView()
}
