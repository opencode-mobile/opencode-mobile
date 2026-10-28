#if HIGHLIGHT_LAB
import SwiftUI

/// One data set on two engines: stacked on iPhone, side by side on iPad, scrolled together,
/// with differing ranges marked and the agreement share shown.
struct HighlightLabCompareView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var model: HighlightLabCompareModel
    @State private var showsDataSetPicker = false
    /// The top line of both panes; either pane's scrolling moves the other.
    @State private var topLine: Int?
    private let autoRun: Bool

    init(service: any HighlightLabService, autoRun: Bool = false) {
        self.autoRun = autoRun
        _model = State(initialValue: HighlightLabCompareModel(service: service))
    }

    var body: some View {
        let state = model.state
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "Compare",
                subtitle: state.dataSetName,
                leading: .back(action: { dismiss() }),
                actions: [
                    .label(title: "Compare", enabled: state.canCompare) {
                        topLine = nil
                        Task { await model.compare() }
                    },
                ],
                showDivider: true
            )
            controls(state)
            Rectangle()
                .fill(OpenCodeColors.borderWeakerBase)
                .frame(height: 1)
            content(state)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .task { if autoRun { await model.compare() } }
        .openCodeBottomSheet(isPresented: $showsDataSetPicker, title: "Data set", scrollTargetID: state.dataSetID, height: .half) {
            ForEach(state.dataSets) { dataSet in
                OpenCodeBottomSheetEntry(
                    title: dataSet.displayName,
                    subtitle: "\(dataSet.sizeClass.label) · \(dataSet.kind.label)",
                    selected: dataSet.id == state.dataSetID,
                    action: {
                        model.selectDataSet(dataSet.id)
                        showsDataSetPicker = false
                    }
                )
                .id(dataSet.id)
            }
        }
    }

    private func controls(_ state: HighlightLabCompareUiState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            OpenCodeListItem(
                title: state.dataSetName,
                subtitle: state.dataSetID,
                style: .card,
                startDecorator: .icon(.description, accessibilityLabel: "Data set"),
                accessory: .disclosure,
                action: { showsDataSetPicker = true }
            )
            .padding(.horizontal, 16)
            HighlightLabChipRow(
                title: "Baseline",
                options: state.engines.map { ($0.id, $0.name) },
                selected: state.baselineEngineID,
                onSelect: { if let id = $0 { model.selectBaseline(id) } }
            )
            HighlightLabChipRow(
                title: "Candidate",
                options: state.engines.map { ($0.id, $0.name) },
                selected: state.candidateEngineID,
                onSelect: { if let id = $0 { model.selectCandidate(id) } }
            )
        }
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private func content(_ state: HighlightLabCompareUiState) -> some View {
        switch state.phase {
        case .idle:
            OpenCodeEmptyState(
                title: "Pick a data set and two engines",
                message: "Compare highlights it on both and marks the lines where their colors differ.",
                icon: .architecture,
                primaryAction: OpenCodeEmptyStateAction(title: "Compare") {
                    Task { await model.compare() }
                }
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .comparing:
            OpenCodeStatusStack(spinner: "Comparing")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case let .ready(comparison):
            VStack(spacing: 0) {
                summary(comparison)
                panes(comparison)
            }
        }
    }

    private func summary(_ comparison: HighlightLabComparison) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                if let agreement = comparison.agreement {
                    Text("Agreement \(HighlightLabFormat.percent(agreement.agreement))")
                        .font(OpenCodeTypography.heading)
                        .foregroundStyle(OpenCodeColors.textStrong)
                    Text("\(HighlightLabFormat.count(agreement.differingRanges.count)) differing ranges · \(HighlightLabFormat.count(agreement.differingCharacters)) characters · \(HighlightLabFormat.count(comparison.markedLines.count)) lines marked")
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textWeak)
                } else {
                    Text("No agreement: an engine returned no spans")
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.warning)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("highlightLab.agreement")
            Spacer(minLength: 0)
            OpenCodeButton(
                variant: .compactText(title: "Next difference"),
                enabled: !comparison.markedLines.isEmpty,
                contrast: .low,
                action: { topLine = model.nextDifference(after: topLine) }
            )
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    @ViewBuilder
    private func panes(_ comparison: HighlightLabComparison) -> some View {
        if horizontalSizeClass == .regular {
            HStack(spacing: 0) {
                pane(comparison.baseline, marks: comparison.lineMarks)
                Rectangle().fill(OpenCodeColors.borderWeakerBase).frame(width: 1)
                pane(comparison.candidate, marks: comparison.lineMarks)
            }
        } else {
            VStack(spacing: 0) {
                pane(comparison.baseline, marks: comparison.lineMarks)
                Rectangle().fill(OpenCodeColors.borderWeakerBase).frame(height: 1)
                pane(comparison.candidate, marks: comparison.lineMarks)
            }
        }
    }

    private func pane(_ pane: HighlightLabComparePane, marks: [Int: [Range<Int>]]) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Text(pane.timing.engineName)
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)
                Text("\(pane.timing.elapsedLabel) · \(pane.timing.outcome ?? pane.timing.status) · \(HighlightLabFormat.count(pane.timing.spanCount)) spans")
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(OpenCodeColors.surfaceBase)
            OpenCodeCodeFile(
                lines: pane.rendered.lines,
                lineSpans: pane.rendered.lineSpans,
                lineMarks: marks,
                maxLineLength: pane.rendered.maxLineLength,
                topLine: $topLine
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
#endif
