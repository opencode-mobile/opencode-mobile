import SwiftUI

/// Column tint behind one band-chart point.
enum OpenCodeBandChartBackground: Equatable, Sendable {
    case none
    case warning
    case error
    /// Not measured: faint stripes.
    case unobserved
}

struct OpenCodeBandChartPoint: Equatable, Sendable {
    let median: Double?
    let upper: Double?
    let background: OpenCodeBandChartBackground

    init(median: Double?, upper: Double?, background: OpenCodeBandChartBackground = .none) {
        self.median = median
        self.upper = upper
        self.background = background
    }
}

/// A median line with a band up to an upper value (such as p95), over tinted columns.
/// The y axis runs from zero to a rounded maximum, labelled at the top and the dashed midline.
struct OpenCodeBandChart: View {
    let points: [OpenCodeBandChartPoint]
    let valueLabel: (Double) -> String
    let accessibilityLabel: OpenCodeText?

    init(
        points: [OpenCodeBandChartPoint],
        valueLabel: @escaping (Double) -> String,
        accessibilityLabel: OpenCodeText? = nil
    ) {
        self.points = points
        self.valueLabel = valueLabel
        self.accessibilityLabel = accessibilityLabel
    }

    /// Rounded maximum of the y axis: max(upper) plus 8%, at least 40.
    static func axisMaximum(_ points: [OpenCodeBandChartPoint]) -> Double {
        let highest = points.compactMap { $0.upper ?? $0.median }.max() ?? 0
        return max(40, OpenCodeChartMetrics.niceCeiling(highest * 1.08))
    }

    var body: some View {
        let maximum = Self.axisMaximum(points)
        Canvas { context, size in
            draw(in: size, maximum: maximum, context: &context)
        }
        .overlay(alignment: .topTrailing) {
            GeometryReader { proxy in
                ZStack(alignment: .topTrailing) {
                    axisLabel(valueLabel(maximum))
                        .frame(maxWidth: .infinity, alignment: .trailing)
                    axisLabel(valueLabel(maximum / 2))
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .offset(y: proxy.size.height / 2)
                }
                .padding(.trailing, 2)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(openCode: accessibilityLabel ?? .empty)
        .accessibilityHidden(accessibilityLabel == nil)
    }

    private func axisLabel(_ text: String) -> some View {
        Text(verbatim: text)
            .font(.system(size: 10).monospacedDigit())
            .foregroundStyle(OpenCodeColors.textWeak)
            .padding(.top, 1)
    }

    private func draw(in size: CGSize, maximum: Double, context: inout GraphicsContext) {
        let count = points.count
        guard count > 0, size.width > 0, size.height > 0 else { return }
        let column = size.width / CGFloat(count)
        func x(_ index: Int) -> CGFloat { (CGFloat(index) + 0.5) * column }
        func y(_ value: Double) -> CGFloat {
            size.height * (1 - CGFloat(min(max(value / maximum, 0), 1)))
        }

        for (index, point) in points.enumerated() {
            let rect = CGRect(x: CGFloat(index) * column, y: 0, width: column, height: size.height)
            switch point.background {
            case .none:
                break
            case .warning:
                context.fill(Path(rect), with: .color(OpenCodeColors.warning.opacity(0.12)))
            case .error:
                context.fill(Path(rect), with: .color(OpenCodeColors.error.opacity(0.16)))
            case .unobserved:
                OpenCodeChartMetrics.drawUnobserved(in: rect, context: &context)
            }
        }

        var midline = Path()
        midline.move(to: CGPoint(x: 0, y: size.height / 2))
        midline.addLine(to: CGPoint(x: size.width, y: size.height / 2))
        context.stroke(midline, with: .color(OpenCodeColors.borderWeakBase), style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
        var baseline = Path()
        baseline.move(to: CGPoint(x: 0, y: size.height - 0.5))
        baseline.addLine(to: CGPoint(x: size.width, y: size.height - 0.5))
        context.stroke(baseline, with: .color(OpenCodeColors.borderWeakBase), lineWidth: 1)

        // Runs of points with a median, so missing values break the line and band.
        var runs: [[(index: Int, median: Double, upper: Double)]] = []
        var current: [(index: Int, median: Double, upper: Double)] = []
        for (index, point) in points.enumerated() {
            if let median = point.median {
                current.append((index, median, max(point.upper ?? median, median)))
            } else if !current.isEmpty {
                runs.append(current)
                current = []
            }
        }
        if !current.isEmpty { runs.append(current) }

        let interactive = OpenCodeColors.interactive
        for run in runs {
            if run.count == 1, let only = run.first {
                let left = x(only.index) - column * 0.35
                let right = x(only.index) + column * 0.35
                let band = CGRect(x: left, y: y(only.upper), width: right - left, height: max(0, y(only.median) - y(only.upper)))
                context.fill(Path(band), with: .color(interactive.opacity(0.18)))
                var tick = Path()
                tick.move(to: CGPoint(x: left, y: y(only.median)))
                tick.addLine(to: CGPoint(x: right, y: y(only.median)))
                context.stroke(tick, with: .color(interactive), lineWidth: OpenCodeChartMetrics.lineWidth)
                continue
            }
            var band = Path()
            band.move(to: CGPoint(x: x(run[0].index), y: y(run[0].upper)))
            for item in run.dropFirst() {
                band.addLine(to: CGPoint(x: x(item.index), y: y(item.upper)))
            }
            for item in run.reversed() {
                band.addLine(to: CGPoint(x: x(item.index), y: y(item.median)))
            }
            band.closeSubpath()
            context.fill(band, with: .color(interactive.opacity(0.18)))
            var line = Path()
            line.addLines(run.map { CGPoint(x: x($0.index), y: y($0.median)) })
            context.stroke(
                line,
                with: .color(interactive),
                style: StrokeStyle(lineWidth: OpenCodeChartMetrics.lineWidth, lineCap: .round, lineJoin: .round)
            )
        }
    }
}

#Preview("Band chart") {
    OpenCodeBandChart(
        points: (0 ..< 48).map { index in
            if (20 ..< 24).contains(index) { return OpenCodeBandChartPoint(median: nil, upper: nil, background: .unobserved) }
            if index == 30 { return OpenCodeBandChartPoint(median: nil, upper: nil, background: .error) }
            let base = 22 + Double(index % 7) * 2
            return OpenCodeBandChartPoint(median: base, upper: base + 18, background: index == 31 ? .warning : .none)
        },
        valueLabel: { "\(Int($0)) ms" }
    )
    .frame(height: 120)
    .padding()
    .openCodeTheme()
}
