import SwiftUI

struct ChartsGalleryView: View {
    static let latencies: [Double?] = [24, 22, 28, 31, 26, 25, nil, nil, 40, 33, 29, 27, 25, 23, 26, 24]

    static var bandPoints: [OpenCodeBandChartPoint] {
        (0 ..< 48).map { index in
            if (18 ..< 22).contains(index) {
                return OpenCodeBandChartPoint(median: nil, upper: nil, background: .unobserved)
            }
            if index == 30 {
                return OpenCodeBandChartPoint(median: nil, upper: nil, background: .error)
            }
            let base = 22 + Double(index % 7) * 2
            return OpenCodeBandChartPoint(median: base, upper: base + 16, background: index == 31 ? .warning : .none)
        }
    }

    static var strip: [OpenCodeStatusStripSegment] {
        (0 ..< 96).map { index in
            switch index % 23 {
            case 0: .error
            case 1, 2: .warning
            case 10 ..< 14: .unobserved
            default: .success
            }
        }
    }

    static var reconnects: [Double] {
        (0 ..< 96).map { (index: Int) -> Double in index % 17 == 0 ? Double(index % 3 + 1) : 0 }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            section("Sparkline") { Self.sparklines() }
            section("Chart Card · Band Chart") { Self.bandCard() }
            section("Chart Card · Status Strip") { Self.stripCard() }
            section("Chart Card · Bar Chart") { Self.barCard() }
            section("Stat Tile") { Self.statTiles() }
            section("Proportion Row") { Self.proportionRows() }
            section("List Item footer") { Self.footerItem() }
        }
    }


    @ViewBuilder
    static func sparklines() -> some View {
        VStack(spacing: 12) {
            OpenCodeSparkline(values: Self.latencies, tone: .success, showsGaps: true)
                .frame(height: 40)
            OpenCodeSparkline(values: Self.latencies, tone: .warning, showsGaps: false)
                .frame(height: 40)
            OpenCodeSparkline(values: [80, 95, 120, 140], tone: .error, showsGaps: true)
                .frame(height: 40)
            OpenCodeSparkline(values: [12, 14, 13, 18, 16], tone: .interactive, showsGaps: true)
                .frame(height: 40)
        }
    }

    @ViewBuilder
    static func bandCard() -> some View {
        OpenCodeChartCard(
            title: .verbatim("Latency"),
            summary: .verbatim("median 24 ms · p95 41 ms"),
            axisLabels: OpenCodeChartAxisLabels(start: .verbatim("09:00"), middle: .verbatim("21:00"), end: .verbatim("now")),
            legend: [
                OpenCodeChartLegendItem(label: .verbatim("Median"), swatch: .interactive),
                OpenCodeChartLegendItem(label: .verbatim("p95"), swatch: .interactiveWeak),
                OpenCodeChartLegendItem(label: .verbatim("Offline"), swatch: .error),
                OpenCodeChartLegendItem(label: .verbatim("Not measured"), swatch: .unobserved)
            ]
        ) {
            OpenCodeBandChart(points: Self.bandPoints, valueLabel: { "\(Int($0)) ms" })
                .frame(height: 120)
        }
    }

    @ViewBuilder
    static func stripCard() -> some View {
        OpenCodeChartCard(
            title: .verbatim("Connection"),
            summary: .verbatim("98.7% live"),
            legend: [
                OpenCodeChartLegendItem(label: .verbatim("Live"), swatch: .success),
                OpenCodeChartLegendItem(label: .verbatim("Unsteady"), swatch: .warning),
                OpenCodeChartLegendItem(label: .verbatim("Down"), swatch: .error),
                OpenCodeChartLegendItem(label: .verbatim("Not measured"), swatch: .unobserved)
            ]
        ) {
            VStack(spacing: 8) {
                OpenCodeStatusStrip(segments: Self.strip)
                OpenCodeStatusStrip(segments: Array(Self.strip.prefix(24)))
            }
        }
    }

    @ViewBuilder
    static func barCard() -> some View {
        OpenCodeChartCard(title: .verbatim("Reconnects"), summary: .verbatim("7 reconnects")) {
            OpenCodeBarChart(values: Self.reconnects, unobservedSlots: Set(40 ..< 44))
                .frame(height: 56)
        }
    }

    @ViewBuilder
    static func statTiles() -> some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                OpenCodeStatTile(label: .verbatim("Uptime"), value: "99.82%", detail: .verbatim("of 6 h 12 m measured"))
                OpenCodeStatTile(label: .verbatim("Reconnects"), value: "2", detail: .verbatim("back in 4.2 s median"))
            }
            .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                OpenCodeStatTile(label: .verbatim("Error rate"), value: "0.4%")
                OpenCodeStatTile(label: .verbatim("Between drops"), value: "—", detail: .verbatim("no drops"))
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    static func proportionRows() -> some View {
        VStack(spacing: 10) {
            OpenCodeProportionRow(label: .verbatim("Network"), count: "6", fraction: 0.6, tone: .warning)
            OpenCodeProportionRow(label: .verbatim("Server error"), count: "3", fraction: 0.3, tone: .info)
            OpenCodeProportionRow(label: .verbatim("Authentication"), count: "1", fraction: 0.1, tone: .error)
        }
    }

    @ViewBuilder
    static func footerItem() -> some View {
        OpenCodeListItem(
            title: .verbatim("Workstation"),
            subtitle: .verbatim("192.168.1.2:4096"),
            status: OpenCodeListItemStatus(text: .verbatim("Active"), tone: .interactive),
            style: .card,
            startDecorator: .letter(Character("W"), .blue),
            action: {},
            footer: AnyView(
                OpenCodeSparkline(values: Self.latencies, tone: .success, showsGaps: true)
                    .frame(height: 40)
            )
        )
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: title)
                .font(OpenCodeTypography.caption.weight(.semibold))
                .foregroundStyle(OpenCodeColors.textWeak)
            content()
        }
    }
}

#Preview {
    ScrollView {
        ChartsGalleryView()
            .padding()
    }
    .openCodeTheme()
}
