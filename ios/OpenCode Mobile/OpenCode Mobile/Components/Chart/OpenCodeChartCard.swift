import SwiftUI

/// Colour swatch beside a chart legend label.
enum OpenCodeChartLegendSwatch: Equatable, Sendable {
    case success
    case warning
    case error
    case interactive
    /// The band behind a line: the interactive colour, faint.
    case interactiveWeak
    /// Not measured.
    case unobserved
}

struct OpenCodeChartLegendItem: Equatable {
    let label: OpenCodeText
    let swatch: OpenCodeChartLegendSwatch

    init(label: OpenCodeText, swatch: OpenCodeChartLegendSwatch) {
        self.label = label
        self.swatch = swatch
    }
}

/// Time labels under a chart: start on the left, optional middle, end on the right.
struct OpenCodeChartAxisLabels: Equatable {
    let start: OpenCodeText
    let middle: OpenCodeText?
    let end: OpenCodeText

    init(start: OpenCodeText, middle: OpenCodeText? = nil, end: OpenCodeText) {
        self.start = start
        self.middle = middle
        self.end = end
    }
}

/// Card around one chart: title and summary on top, the chart, then axis labels and legend.
struct OpenCodeChartCard<Content: View>: View {
    let title: OpenCodeText
    let summary: OpenCodeText?
    let axisLabels: OpenCodeChartAxisLabels?
    let legend: [OpenCodeChartLegendItem]
    @ViewBuilder let content: () -> Content

    init(
        title: OpenCodeText,
        summary: OpenCodeText? = nil,
        axisLabels: OpenCodeChartAxisLabels? = nil,
        legend: [OpenCodeChartLegendItem] = [],
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.summary = summary
        self.axisLabels = axisLabels
        self.legend = legend
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(openCode: title)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if let summary {
                    Text(openCode: summary)
                        .font(OpenCodeTypography.caption.monospacedDigit())
                        .foregroundStyle(OpenCodeColors.textBase)
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                }
            }
            .accessibilityElement(children: .combine)

            content()

            if let axisLabels {
                OpenCodeChartAxis(labels: axisLabels)
            }

            if !legend.isEmpty {
                OpenCodeChipFlowLayout(spacing: 12, lineSpacing: 6) {
                    ForEach(Array(legend.enumerated()), id: \.offset) { _, item in
                        OpenCodeChartLegendLabel(item: item)
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .openCodeChartCardSurface()
    }
}

struct OpenCodeChartAxis: View {
    let labels: OpenCodeChartAxisLabels

    var body: some View {
        HStack(spacing: 4) {
            label(labels.start)
            Spacer(minLength: 4)
            if let middle = labels.middle {
                label(middle)
                Spacer(minLength: 4)
            }
            label(labels.end)
        }
        .accessibilityHidden(true)
    }

    private func label(_ text: OpenCodeText) -> some View {
        Text(openCode: text)
            .font(.system(size: 11).monospacedDigit())
            .foregroundStyle(OpenCodeColors.textWeak)
            .lineLimit(1)
    }
}

private struct OpenCodeChartLegendLabel: View {
    let item: OpenCodeChartLegendItem

    var body: some View {
        HStack(spacing: 5) {
            swatch
                .frame(width: 10, height: 10)
            Text(openCode: item.label)
                .font(.system(size: 11))
                .foregroundStyle(OpenCodeColors.textBase)
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private var swatch: some View {
        switch item.swatch {
        case .success:
            RoundedRectangle(cornerRadius: 2).fill(OpenCodeColors.success)
        case .warning:
            RoundedRectangle(cornerRadius: 2).fill(OpenCodeColors.warning)
        case .error:
            RoundedRectangle(cornerRadius: 2).fill(OpenCodeColors.error)
        case .interactive:
            Capsule().fill(OpenCodeColors.interactive).frame(height: 2)
        case .interactiveWeak:
            RoundedRectangle(cornerRadius: 2).fill(OpenCodeColors.interactive.opacity(0.18))
        case .unobserved:
            Canvas { context, size in
                OpenCodeChartMetrics.drawUnobserved(in: CGRect(origin: .zero, size: size), context: &context)
            }
            .clipShape(RoundedRectangle(cornerRadius: 2))
            .overlay(RoundedRectangle(cornerRadius: 2).stroke(OpenCodeColors.borderWeakBase, lineWidth: 0.5))
        }
    }
}

extension View {
    /// Surface of chart cards and stat tiles.
    func openCodeChartCardSurface() -> some View {
        background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: OpenCodeRadius.medium))
            .overlay(
                RoundedRectangle(cornerRadius: OpenCodeRadius.medium)
                    .stroke(OpenCodeColors.borderWeakBase, lineWidth: 1)
            )
    }
}

#Preview("Chart card") {
    OpenCodeChartCard(
        title: .verbatim("Connection"),
        summary: .verbatim("99.2% live"),
        axisLabels: OpenCodeChartAxisLabels(start: .verbatim("24 h ago"), middle: .verbatim("12 h"), end: .verbatim("now")),
        legend: [
            OpenCodeChartLegendItem(label: .verbatim("Live"), swatch: .success),
            OpenCodeChartLegendItem(label: .verbatim("Unsteady"), swatch: .warning),
            OpenCodeChartLegendItem(label: .verbatim("Down"), swatch: .error),
            OpenCodeChartLegendItem(label: .verbatim("Not measured"), swatch: .unobserved)
        ]
    ) {
        OpenCodeStatusStrip(segments: Array(repeating: .success, count: 96))
    }
    .padding()
    .openCodeTheme()
}
