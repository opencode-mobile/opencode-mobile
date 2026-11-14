import SwiftUI

/// A small line of recent values, filling the size it is given. Nil values break the line;
/// with `showsGaps` their columns are tinted in the error colour.
struct OpenCodeSparkline: View {
    let values: [Double?]
    let tone: OpenCodeChartTone
    let showsGaps: Bool

    init(values: [Double?], tone: OpenCodeChartTone = .interactive, showsGaps: Bool = true) {
        self.values = values
        self.tone = tone
        self.showsGaps = showsGaps
    }

    var body: some View {
        Canvas { context, size in
            draw(in: size, context: &context)
        }
        .accessibilityHidden(true)
    }

    private func draw(in size: CGSize, context: inout GraphicsContext) {
        let count = values.count
        guard count > 0, size.width > 0, size.height > 0 else { return }
        let present = values.compactMap { $0 }
        let inset: CGFloat = 3
        let plotHeight = max(1, size.height - inset * 2)
        let step = count > 1 ? size.width / CGFloat(count - 1) : size.width
        func x(_ index: Int) -> CGFloat {
            count > 1 ? CGFloat(index) * step : size.width / 2
        }

        if showsGaps {
            let columnWidth = count > 1 ? step : size.width
            for (index, value) in values.enumerated() where value == nil {
                let rect = CGRect(x: x(index) - columnWidth / 2, y: 0, width: columnWidth, height: size.height)
                    .intersection(CGRect(origin: .zero, size: size))
                context.fill(Path(rect), with: .color(OpenCodeColors.error.opacity(0.14)))
            }
        }

        guard let low = present.min(), let high = present.max() else { return }
        let spread = high - low
        let lower = spread > 0 ? max(0, low - spread * 0.25) : 0
        let upper = spread > 0 ? high + spread * 0.15 : max(high * 1.25, 1)
        func y(_ value: Double) -> CGFloat {
            let fraction = (value - lower) / max(upper - lower, .ulpOfOne)
            return inset + plotHeight * (1 - CGFloat(fraction))
        }

        // Runs of consecutive values, so a nil breaks the line.
        var runs: [[CGPoint]] = []
        var current: [CGPoint] = []
        for (index, value) in values.enumerated() {
            if let value {
                current.append(CGPoint(x: x(index), y: y(value)))
            } else if !current.isEmpty {
                runs.append(current)
                current = []
            }
        }
        if !current.isEmpty { runs.append(current) }

        let color = tone.color
        let gradient = Gradient(colors: [color.opacity(0.28), color.opacity(0)])
        for run in runs {
            if run.count == 1, let point = run.first {
                context.fill(
                    Path(ellipseIn: CGRect(x: point.x - 1.5, y: point.y - 1.5, width: 3, height: 3)),
                    with: .color(color)
                )
                continue
            }
            var line = Path()
            line.addLines(run)
            var area = line
            area.addLine(to: CGPoint(x: run[run.count - 1].x, y: size.height))
            area.addLine(to: CGPoint(x: run[0].x, y: size.height))
            area.closeSubpath()
            context.fill(
                area,
                with: .linearGradient(gradient, startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: 0, y: size.height))
            )
            context.stroke(
                line,
                with: .color(color),
                style: StrokeStyle(lineWidth: OpenCodeChartMetrics.lineWidth, lineCap: .round, lineJoin: .round)
            )
        }

        if let last = values.last, let last {
            let point = CGPoint(x: x(count - 1), y: y(last))
            context.fill(Path(ellipseIn: CGRect(x: point.x - 3, y: point.y - 3, width: 6, height: 6)), with: .color(color))
        }
    }
}

#Preview("Sparkline") {
    VStack(spacing: 16) {
        OpenCodeSparkline(values: [24, 22, 28, 31, 26, nil, nil, 40, 33, 29, 27, 25], tone: .success)
            .frame(height: 40)
        OpenCodeSparkline(values: [60, 80, 120, 90, nil, 140, 110], tone: .warning)
            .frame(height: 40)
    }
    .padding()
    .openCodeTheme()
}
