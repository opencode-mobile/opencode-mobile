#if HIGHLIGHT_LAB
import Foundation
import Observation

nonisolated enum HighlightLabViewerPhase: Equatable, Sendable {
    case highlighting
    case ready(HighlightLabRenderedText)
}

nonisolated struct HighlightLabViewerUiState: Equatable, Sendable {
    let title: String
    let subtitle: String
    let engines: [HighlightLabEngineOption]
    var engineID: String
    var phase: HighlightLabViewerPhase = .highlighting
    /// The last call's timing; kept while the next call runs.
    var timing: HighlightLabTiming?

    var isHighlighting: Bool { phase == .highlighting }
}

/// Screen Model for one data set shown highlighted with its timing, on a chosen engine.
@MainActor
@Observable
final class HighlightLabViewerModel {
    private(set) var state: HighlightLabViewerUiState

    @ObservationIgnored private let service: any HighlightLabService
    @ObservationIgnored private let input: HighlightLabInput
    @ObservationIgnored private var request = 0
    @ObservationIgnored private var started = false

    init(input: HighlightLabInput, service: any HighlightLabService) {
        self.input = input
        self.service = service
        let title: String
        let subtitle: String
        switch input {
        case let .catalog(id):
            let info = service.dataSets.first { $0.id == id }
            title = info?.displayName ?? id
            subtitle = id
        case let .custom(text):
            title = text.displayName
            subtitle = "\(text.language) · \(HighlightLabFormat.count(text.source.utf16.count)) characters"
        }
        state = HighlightLabViewerUiState(
            title: title,
            subtitle: subtitle,
            engines: service.engines,
            engineID: service.engines.first?.id ?? ""
        )
    }

    func start() async {
        guard !started else { return }
        started = true
        await highlight()
    }

    func selectEngine(_ id: String) async {
        guard id != state.engineID else { return }
        state.engineID = id
        await highlight()
    }

    /// Highlights again on the selected engine, now warm.
    func runAgain() async {
        await highlight()
    }

    private func highlight() async {
        request += 1
        let current = request
        state.phase = .highlighting
        let result = await service.highlight(input, engineID: state.engineID)
        let rendered = await HighlightLabRenderedText.make(source: result.source, spans: result.spans)
        // A newer engine choice or run supersedes this one.
        guard current == request else { return }
        state.timing = result.timing
        state.phase = .ready(rendered)
    }
}
#endif
