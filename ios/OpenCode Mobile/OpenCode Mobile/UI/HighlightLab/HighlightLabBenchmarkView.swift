#if HIGHLIGHT_LAB
import SwiftUI

/// Runs a tier or picked data sets on engines in order, shows progress and result tables,
/// and shares the Markdown and JSON reports.
struct HighlightLabBenchmarkView: View {
    let model: HighlightLabBenchmarkModel

    @Environment(\.dismiss) private var dismiss
    @State private var share: HighlightLabShareItem?

    var body: some View {
        let state = model.state
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Benchmark",
                subtitle: .verbatim(HighlightLabEnvironment.buildLabel),
                leading: .back(action: { dismiss() }),
                showDivider: true
            )
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    plan(state)
                    engines(state)
                    runControls(state)
                    if let message = state.errorMessage {
                        OpenCodeInlineNotice(message: .verbatim(message), tone: .error, variant: .banner)
                            .padding(.horizontal, 16)
                    }
                    results(state)
                }
                .padding(.vertical, 12)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .sheet(item: $share) { item in
            ActivityShareView(items: [item.url])
        }
    }

    @ViewBuilder
    private func plan(_ state: HighlightLabBenchmarkUiState) -> some View {
        OpenCodeSectionHeader(title: "Plan")
        HighlightLabChipRow(
            title: "Run",
            options: HighlightLabBenchmarkMode.allCases.map { ($0, $0.label) },
            selected: state.mode,
            enabled: !state.phase.isRunning,
            onSelect: { if let mode = $0 { model.setMode(mode) } }
        )
        if state.mode == .selected {
            Text("\(state.selectedDataSetIDs.count) selected; each runs its default measurement")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .padding(.horizontal, 16)
            ForEach(state.dataSets) { dataSet in
                let selected = state.selectedDataSetIDs.contains(dataSet.id)
                OpenCodeListItem(
                    title: .verbatim(dataSet.displayName),
                    subtitle: "\(dataSet.sizeClass.label) · \(dataSet.kind.label)",
                    selected: selected,
                    enabled: !state.phase.isRunning,
                    style: .card,
                    startDecorator: .icon(selected ? .check : .description, accessibilityLabel: selected ? "Selected" : "Not selected"),
                    action: { model.toggleDataSet(dataSet.id) }
                )
                .padding(.horizontal, 16)
            }
        }
    }

    @ViewBuilder
    private func engines(_ state: HighlightLabBenchmarkUiState) -> some View {
        OpenCodeSectionHeader(
            title: "Engines",
            trailing: state.engineOrder.count > 1 && !state.phase.isRunning ? .action("Reverse order") { model.reverseEngines() } : nil
        )
        ForEach(state.engines) { engine in
            let position = state.position(of: engine.id)
            OpenCodeListItem(
                title: .verbatim(engine.name),
                subtitle: position.map { $0 == 0 ? "1 · Baseline" : "\($0 + 1) · Compared with the baseline" } ?? "Not in this run",
                selected: position != nil,
                enabled: !state.phase.isRunning,
                style: .card,
                startDecorator: .icon(position == nil ? .description : .check, accessibilityLabel: position == nil ? "Off" : "On"),
                action: { model.toggleEngine(engine.id) }
            )
            .padding(.horizontal, 16)
        }
    }

    @ViewBuilder
    private func runControls(_ state: HighlightLabBenchmarkUiState) -> some View {
        switch state.phase {
        case .idle:
            OpenCodeButton(
                variant: .fullWidthText(title: "Run benchmark"),
                size: .large,
                enabled: state.canRun,
                action: model.start
            )
            .padding(.horizontal, 16)
            .accessibilityIdentifier("highlightLab.run")
        case let .running(progress):
            VStack(alignment: .leading, spacing: 8) {
                if let progress {
                    ProgressView(value: Double(progress.completedSteps), total: Double(max(1, progress.totalSteps)))
                        .tint(OpenCodeColors.interactive)
                    Text("Case \(progress.caseNumber) of \(progress.caseCount) · \(progress.engineName)")
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    Text(progress.caseTitle)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                } else {
                    Text("Starting…")
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textStrong)
                }
                OpenCodeButton(
                    variant: .fullWidthText(title: "Stop"),
                    size: .large,
                    contrast: .destructive,
                    action: model.stop
                )
            }
            .padding(.horizontal, 16)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("highlightLab.progress")
        case .stopping:
            Text("Stopping after the current call…")
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textStrong)
                .padding(.horizontal, 16)
        }
    }

    @ViewBuilder
    private func results(_ state: HighlightLabBenchmarkUiState) -> some View {
        if let tables = state.tables {
            OpenCodeSectionHeader(title: state.phase.isRunning ? "Results so far" : "Last report")
            VStack(alignment: .leading, spacing: 4) {
                Text(tables.title)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text(tables.complete ? "Complete" : state.phase.isRunning ? "Partial: still running" : "Partial: stopped")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                if let saved = state.savedFiles, !state.phase.isRunning {
                    Text("Saved \(saved.stampedMarkdown.lastPathComponent) in Documents/highlight-lab")
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                }
            }
            .padding(.horizontal, 16)
            if let report = state.report, !state.phase.isRunning {
                HStack(spacing: 8) {
                    OpenCodeButton(variant: .compactText(title: "Share Markdown"), contrast: .low) {
                        share = HighlightLabShareItem.write(report.markdown, name: "highlight-lab-report.md")
                    }
                    OpenCodeButton(variant: .compactText(title: "Share JSON"), contrast: .low) {
                        share = HighlightLabShareItem.write(report.json, name: "highlight-lab-report.json")
                    }
                }
                .padding(.horizontal, 16)
            }
            ForEach(tables.sections) { table in
                HighlightLabTableView(table: table)
            }
            if let comparison = tables.comparison {
                HighlightLabTableView(table: comparison)
            }
        }
    }
}

/// A report copied to a temporary file for the share sheet.
struct HighlightLabShareItem: Identifiable {
    let url: URL
    var id: String { url.path }

    static func write(_ text: String, name: String) -> HighlightLabShareItem? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            try Data(text.utf8).write(to: url, options: .atomic)
            return HighlightLabShareItem(url: url)
        } catch {
            return nil
        }
    }
}
#endif
