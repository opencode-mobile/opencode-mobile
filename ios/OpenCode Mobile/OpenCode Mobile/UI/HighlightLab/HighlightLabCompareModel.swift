#if HIGHLIGHT_LAB
import Foundation
import Observation

/// One engine's side of a comparison.
nonisolated struct HighlightLabComparePane: Equatable, Sendable {
    let rendered: HighlightLabRenderedText
    let timing: HighlightLabTiming
}

nonisolated struct HighlightLabComparison: Equatable, Sendable {
    let baseline: HighlightLabComparePane
    let candidate: HighlightLabComparePane
    /// Nil when either engine returned no spans, so nothing could be compared.
    let agreement: HighlightLabAgreement?
    /// Differing ranges per display line of the shared source.
    let lineMarks: [Int: [Range<Int>]]

    var markedLines: [Int] { lineMarks.keys.sorted() }
}

nonisolated enum HighlightLabComparePhase: Equatable, Sendable {
    case idle
    case comparing
    case ready(HighlightLabComparison)
}

nonisolated struct HighlightLabCompareUiState: Equatable, Sendable {
    let dataSets: [HighlightLabDataSetInfo]
    let engines: [HighlightLabEngineOption]
    var dataSetID: String
    var baselineEngineID: String
    var candidateEngineID: String
    var phase: HighlightLabComparePhase = .idle

    var dataSetName: String {
        dataSets.first { $0.id == dataSetID }?.displayName ?? dataSetID
    }

    var canCompare: Bool {
        phase != .comparing && !dataSetID.isEmpty && !baselineEngineID.isEmpty && !candidateEngineID.isEmpty
    }
}

/// Screen Model for one data set on two engines: both highlighted, differing ranges marked.
@MainActor
@Observable
final class HighlightLabCompareModel {
    private(set) var state: HighlightLabCompareUiState

    @ObservationIgnored private let service: any HighlightLabService
    @ObservationIgnored private var request = 0

    init(service: any HighlightLabService, dataSetID: String? = nil) {
        self.service = service
        let defaultSet = service.dataSets.first { $0.id == "typical/kotlin/16k" } ?? service.dataSets.first
        state = HighlightLabCompareUiState(
            dataSets: service.dataSets,
            engines: service.engines,
            dataSetID: dataSetID ?? defaultSet?.id ?? "",
            baselineEngineID: service.engines.first?.id ?? "",
            candidateEngineID: service.engines.dropFirst().first?.id ?? service.engines.first?.id ?? ""
        )
    }

    func selectDataSet(_ id: String) {
        guard id != state.dataSetID else { return }
        state.dataSetID = id
        invalidate()
    }

    func selectBaseline(_ id: String) {
        guard id != state.baselineEngineID else { return }
        state.baselineEngineID = id
        invalidate()
    }

    func selectCandidate(_ id: String) {
        guard id != state.candidateEngineID else { return }
        state.candidateEngineID = id
        invalidate()
    }

    func swapEngines() {
        (state.baselineEngineID, state.candidateEngineID) = (state.candidateEngineID, state.baselineEngineID)
        invalidate()
    }

    /// Highlights the data set on both engines, one after the other, then diffs their spans.
    func compare() async {
        guard state.canCompare else { return }
        request += 1
        let current = request
        state.phase = .comparing
        let input = HighlightLabInput.catalog(id: state.dataSetID)
        let baseline = await service.highlight(input, engineID: state.baselineEngineID)
        let candidate = await service.highlight(input, engineID: state.candidateEngineID)
        var agreement: HighlightLabAgreement?
        if let baselineSpans = baseline.spans, let candidateSpans = candidate.spans {
            agreement = await service.agreement(length: baseline.source.utf16.count, baseline: baselineSpans, candidate: candidateSpans)
        }
        let baselineText = await HighlightLabRenderedText.make(source: baseline.source, spans: baseline.spans)
        let candidateText = await HighlightLabRenderedText.make(source: candidate.source, spans: candidate.spans)
        guard current == request else { return }
        state.phase = .ready(
            HighlightLabComparison(
                baseline: HighlightLabComparePane(rendered: baselineText, timing: baseline.timing),
                candidate: HighlightLabComparePane(rendered: candidateText, timing: candidate.timing),
                agreement: agreement,
                lineMarks: baselineText.lineMarks(agreement?.differingRanges ?? [])
            )
        )
    }

    /// The first marked line after `line`, wrapping to the first one.
    func nextDifference(after line: Int?) -> Int? {
        guard case let .ready(comparison) = state.phase else { return nil }
        let marked = comparison.markedLines
        guard let first = marked.first else { return nil }
        guard let line else { return first }
        return marked.first { $0 > line } ?? first
    }

    private func invalidate() {
        request += 1
        state.phase = .idle
    }
}
#endif
