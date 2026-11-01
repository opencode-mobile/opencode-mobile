#if HIGHLIGHT_LAB
import SwiftUI

nonisolated enum HighlightLabRoute: Hashable {
    case dataSets
    case benchmark
}

/// Highlighting Lab home (#872): data sets and benchmark. Lab builds only.
struct HighlightLabHomeView: View {
    /// Set when shown as the trailing root of the tablet Settings split view.
    var detailControl: OpenCodeSplitPaneDetailControl? = nil
    var service: any HighlightLabService = KMPHighlightLabService.shared

    @Environment(\.dismiss) private var dismiss
    @State private var route: HighlightLabRoute?
    /// Kept here so a running benchmark survives leaving its screen.
    @State private var benchmarkModel: HighlightLabBenchmarkModel?

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Highlighting Lab",
                subtitle: .verbatim(HighlightLabEnvironment.buildLabel),
                leading: OpenCodeSplitPane.detailLeading(detailControl, back: { dismiss() }),
                showDivider: true
            )
            ScrollView {
                VStack(spacing: 8) {
                    entry("Data sets", "Browse bundled inputs, paste text, view them highlighted with timings", icon: .description, route: .dataSets)
                    entry("Benchmark", benchmarkSubtitle, icon: .settings, route: .benchmark)
                }
                .padding(.vertical, 12)
            }
            .openCodeReadableColumn()
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .navigationDestination(item: $route) { route in
            switch route {
            case .dataSets:
                HighlightLabDataSetsView(service: service)
            case .benchmark:
                if let benchmarkModel {
                    HighlightLabBenchmarkView(model: benchmarkModel)
                }
            }
        }
    }

    private var benchmarkSubtitle: String {
        if case .running = benchmarkModel?.state.phase { return "Running…" }
        return "Quick or full tier, or picked data sets"
    }

    private func entry(_ title: String, _ subtitle: String, icon: OpenCodeIcon, route: HighlightLabRoute) -> some View {
        OpenCodeListItem(
            title: .verbatim(title),
            subtitle: .verbatim(subtitle),
            style: .card,
            startDecorator: .icon(icon, accessibilityLabel: .verbatim(title)),
            accessory: .disclosure,
            action: {
                if route == .benchmark, benchmarkModel == nil {
                    benchmarkModel = HighlightLabBenchmarkModel(service: service)
                }
                self.route = route
            }
        )
        .padding(.horizontal, 16)
        .accessibilityIdentifier("highlightLab.\(route)")
    }
}

/// A labeled row of single-choice chips; `nil` is "All" when `allLabel` is set.
struct HighlightLabChipRow<ID: Hashable>: View {
    let title: String
    let options: [(id: ID, label: String)]
    let selected: ID?
    var allLabel: String?
    var enabled = true
    let onSelect: (ID?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    if let allLabel {
                        OpenCodeChoiceBadge(label: .verbatim(allLabel), selected: selected == nil, enabled: enabled) { onSelect(nil) }
                    }
                    ForEach(options, id: \.id) { option in
                        OpenCodeChoiceBadge(label: .verbatim(option.label), selected: selected == option.id, enabled: enabled) {
                            onSelect(option.id)
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}

/// Time to spans, characters per ms, outcome, and span count for one call.
struct HighlightLabTimingStrip: View {
    let timing: HighlightLabTiming?
    let isRunning: Bool
    /// Shown when nothing ran yet.
    var idleCaption = "Not run yet"

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                cell("Time", timing.map(\.elapsedLabel))
                cell("Chars/ms", timing.map { HighlightLabFormat.rate($0.charsPerMs) })
                cell("Outcome", timing.map { $0.outcome ?? "–" })
                cell("Spans", timing.map { HighlightLabFormat.count($0.spanCount) })
            }
            Text(caption)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .lineLimit(2)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("highlightLab.timing")
    }

    private var caption: String {
        if isRunning { return "Highlighting…" }
        guard let timing else { return idleCaption }
        let call = timing.cold ? "first call on a fresh instance" : "warm call"
        return "\(timing.engineName) · \(call) · \(timing.status) · \(HighlightLabFormat.count(timing.characters)) characters"
    }

    private func cell(_ label: String, _ value: String?) -> some View {
        OpenCodeKeyValueRow(label: .verbatim(label), value: value ?? "–", style: .stacked)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One report section as a horizontally scrolling table.
struct HighlightLabTableView: View {
    let table: HighlightLabReportTables.Table

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(table.title)
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
                .padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: true) {
                Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 6) {
                    GridRow {
                        ForEach(Array(table.headers.enumerated()), id: \.offset) { _, header in
                            Text(header)
                                .font(OpenCodeTypography.caption.weight(.semibold))
                                .foregroundStyle(OpenCodeColors.textBase)
                        }
                    }
                    Divider().gridCellUnsizedAxes(.horizontal)
                    ForEach(table.rows) { row in
                        GridRow {
                            ForEach(Array(row.cells.enumerated()), id: \.offset) { _, cell in
                                Text(cell)
                                    .font(OpenCodeTypography.caption.monospacedDigit())
                                    .foregroundStyle(color(row.status))
                                    .lineLimit(1)
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
            }
        }
    }

    private func color(_ status: String) -> Color {
        switch status {
        case "Completed": OpenCodeColors.textStrong
        case "Skipped": OpenCodeColors.textWeaker
        default: OpenCodeColors.warning
        }
    }
}

extension HighlightLabEnvironment {
    static var buildLabel: String {
        #if DEBUG
        "Debug build: timings are unoptimized"
        #else
        "Lab build: optimized"
        #endif
    }
}
#endif
