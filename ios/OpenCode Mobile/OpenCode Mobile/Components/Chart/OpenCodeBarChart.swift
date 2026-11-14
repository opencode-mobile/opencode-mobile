import SwiftUI

/// Bars for counts per time slot, on a baseline, with the maximum labelled top right.
/// A zero draws no bar.
struct OpenCodeBarChart: View {
    let values: [Double]
    let tone: OpenCodeChartTone
    let valueLabel: (Double) -> String

    init(
        values: [Double],
        tone: OpenCodeChartTone = .warning,
        valueLabel: @escaping (Double) -> String = OpenCodeBarChart.integerLabel
    ) {
        self.values = values
        self.tone = tone
        self.valueLabel = valueLabel
    }

    static func integerLabel(_ value: Double) -> String {
        "\(Int(value.rounded()))"
    }

    private var maximum: Double {
        values.max() ?? 0
    }

    var body: some View {
        let maximum = maximum
        Canvas { context, size in
            let count = values.count
            guard count > 0, size.width > 0, size.height > 0 else { return }
            let slot = size.width / CGFloat(count)
            let barWidth = max(0.5, slot * 0.7)
            let top: CGFloat = 14
            let plotHeight = max(1, size.height - top - 1)
            if maximum > 0 {
                for (index, value) in values.enumerated() where value > 0 {
                    let height = max(1.5, plotHeight * CGFloat(value / maximum))
                    let rect = CGRect(
                        x: CGFloat(index) * slot + (slot - barWidth) / 2,
                        y: size.height - 1 - height,
                        width: barWidth,
                        height: height
                    )
                    context.fill(Path(roundedRect: rect, cornerRadius: min(1.5, barWidth / 2)), with: .color(tone.color))
                }
            }
            var baseline = Path()
            baseline.move(to: CGPoint(x: 0, y: size.height - 0.5))
            baseline.addLine(to: CGPoint(x: size.width, y: size.height - 0.5))
            context.stroke(baseline, with: .color(OpenCodeColors.borderWeakBase), lineWidth: 1)
        }
        .overlay(alignment: .topTrailing) {
            if maximum > 0 {
                Text(verbatim: valueLabel(maximum))
                    .font(.system(size: 10).monospacedDigit())
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .padding(.trailing, 2)
            }
        }
        .accessibilityHidden(true)
    }
}

#Preview("Bar chart") {
    OpenCodeBarChart(values: [0, 0, 1, 0, 0, 3, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0])
        .frame(height: 60)
        .padding()
        .openCodeTheme()
}
