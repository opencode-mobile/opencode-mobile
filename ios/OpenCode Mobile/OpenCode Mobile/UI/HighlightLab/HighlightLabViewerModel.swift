#if HIGHLIGHT_LAB
import Foundation
import Observation

/// An engine button: disabled with a short reason for a language the engine cannot highlight.
nonisolated struct HighlightLabViewerEngine: Equatable, Identifiable, Sendable {
    let id: String
    let name: String
    let unsupportedReason: String?

    var isEnabled: Bool { unsupportedReason == nil }
}

nonisolated enum HighlightLabViewerPhase: Equatable, Sendable {
    /// Generating the text; nothing to show yet.
    case loading
    /// Plain text, no engine run.
    case plain
    /// Plain text while `engineID` renders.
    case rendering(engineID: String)
    /// Text colored by `engineID`.
    case highlighted(engineID: String)

    var renderingEngineID: String? {
        if case let .rendering(id) = self { return id }
        return nil
    }

    var highlightedEngineID: String? {
        if case let .highlighted(id) = self { return id }
        return nil
    }
}

nonisolated struct HighlightLabViewerUiState: Equatable, Sendable {
    let title: String
    let subtitle: String
    var engines: [HighlightLabViewerEngine] = []
    var phase: HighlightLabViewerPhase = .loading
    /// What the code file shows: the plain text, or the last highlighted result.
    var rendered: HighlightLabRenderedText?
    /// The shown engine result; nil while plain.
    var timing: HighlightLabTiming?
    /// Engine calls that were cancelled or given up on and have not returned yet.
    var backgroundCalls = 0

    var isRendering: Bool { phase.renderingEngineID != nil }
}

/// Screen Model for one input in the Lab viewer. It opens as plain text; an engine runs only when
/// its button is tapped, off the main actor, and Cancel or another engine ends the run at once.
@MainActor
@Observable
final class HighlightLabViewerModel {
    private(set) var state: HighlightLabViewerUiState

    @ObservationIgnored private let service: any HighlightLabService
    @ObservationIgnored private let input: HighlightLabInput
    @ObservationIgnored private let backgroundPoll: Duration
    @ObservationIgnored private var text: HighlightLabText?
    @ObservationIgnored private var plain: HighlightLabRenderedText?
    @ObservationIgnored private var run = 0
    @ObservationIgnored private var cancellation: HighlightLabCancellation?
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    @ObservationIgnored private var started = false

    init(input: HighlightLabInput, service: any HighlightLabService, backgroundPoll: Duration = .milliseconds(500)) {
        self.input = input
        self.service = service
        self.backgroundPoll = backgroundPoll
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
        state = HighlightLabViewerUiState(title: title, subtitle: subtitle)
    }

    /// Loads the text and shows it plain; no engine runs.
    func start() async {
        guard !started else { return }
        started = true
        let loaded = await service.text(of: input)
        let rendered = await HighlightLabRenderedText.make(source: loaded.source, spans: nil)
        text = loaded
        plain = rendered
        state.engines = service.engines.map { engine in
            HighlightLabViewerEngine(
                id: engine.id,
                name: engine.name,
                unsupportedReason: service.unsupportedReason(engineID: engine.id, language: loaded.language)
            )
        }
        state.rendered = rendered
        if state.phase == .loading { state.phase = .plain }
    }

    /// Renders with `engineID`, ending any run in progress first. Tapping the shown engine runs it again (warm).
    func select(_ engineID: String) {
        guard let text, let plain, state.engines.first(where: { $0.id == engineID })?.isEnabled == true else { return }
        endRun()
        run += 1
        let current = run
        let cancellation = HighlightLabCancellation()
        self.cancellation = cancellation
        state.phase = .rendering(engineID: engineID)
        state.rendered = plain
        state.timing = nil
        Task {
            let result = await service.highlight(text, engineID: engineID, cancellation: cancellation)
            // A cancelled run returns once the guard abandons it; its call may still be busy.
            guard current == run else {
                refreshBackgroundCalls()
                return
            }
            let rendered = await HighlightLabRenderedText.make(source: result.source, spans: result.spans)
            guard current == run else { return }
            self.cancellation = nil
            state.rendered = result.spans == nil ? plain : rendered
            state.timing = result.timing
            state.phase = .highlighted(engineID: engineID)
            refreshBackgroundCalls()
        }
    }

    /// Ends the run in progress and goes back to plain text without waiting for the engine.
    func cancel() {
        guard state.isRendering else { return }
        endRun()
        run += 1
        state.phase = .plain
        state.rendered = plain
        state.timing = nil
    }

    /// Drops the highlighted result and shows the plain text.
    func showPlain() {
        cancel()
        guard state.phase.highlightedEngineID != nil else { return }
        state.phase = .plain
        state.rendered = plain
        state.timing = nil
    }

    private func endRun() {
        cancellation?.cancel()
        cancellation = nil
    }

    /// Counts abandoned calls now and again until they have all returned.
    private func refreshBackgroundCalls() {
        state.backgroundCalls = service.backgroundCalls
        guard pollTask == nil else { return }
        pollTask = Task { [weak self, backgroundPoll] in
            while !Task.isCancelled {
                try? await Task.sleep(for: backgroundPoll)
                guard let self else { return }
                let count = service.backgroundCalls
                state.backgroundCalls = count
                if count == 0, !state.isRendering {
                    pollTask = nil
                    return
                }
            }
        }
    }
}
#endif
