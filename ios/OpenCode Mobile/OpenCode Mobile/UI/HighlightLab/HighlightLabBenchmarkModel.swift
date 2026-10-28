#if HIGHLIGHT_LAB
import Foundation
import Observation

nonisolated enum HighlightLabBenchmarkMode: String, CaseIterable, Hashable, Sendable {
    case quick
    case full
    case selected

    var label: String {
        switch self {
        case .quick: "Quick"
        case .full: "Full"
        case .selected: "Selected data sets"
        }
    }
}

nonisolated enum HighlightLabBenchmarkPhase: Equatable, Sendable {
    case idle
    case running(HighlightLabProgress?)
    case stopping

    var isRunning: Bool { self != .idle }
}

nonisolated struct HighlightLabBenchmarkUiState: Equatable, Sendable {
    let engines: [HighlightLabEngineOption]
    let dataSets: [HighlightLabDataSetInfo]
    var mode: HighlightLabBenchmarkMode = .quick
    var selectedDataSetIDs: Set<String> = []
    /// Engines in run order; the first is the comparison baseline.
    var engineOrder: [String]
    var phase: HighlightLabBenchmarkPhase = .idle
    /// The running run's partial tables, or the last finished report's.
    var tables: HighlightLabReportTables?
    /// The last finished report, saved to app storage.
    var report: HighlightLabReport?
    var savedFiles: HighlightLabStorage.SavedFiles?
    var errorMessage: String?

    var canRun: Bool {
        guard !phase.isRunning, !engineOrder.isEmpty else { return false }
        return mode != .selected || !selectedDataSetIDs.isEmpty
    }

    func position(of engineID: String) -> Int? {
        engineOrder.firstIndex(of: engineID)
    }
}

/// Screen Model for running benchmark tiers or picked data sets on picked engines.
@MainActor
@Observable
final class HighlightLabBenchmarkModel {
    private(set) var state: HighlightLabBenchmarkUiState

    @ObservationIgnored private let service: any HighlightLabService
    @ObservationIgnored private let storage: HighlightLabStorage
    @ObservationIgnored private let environment: @MainActor () -> [String]
    @ObservationIgnored private var run: (any HighlightLabBenchmarkRun)?
    @ObservationIgnored private var runID = 0

    init(
        service: any HighlightLabService,
        storage: HighlightLabStorage = .documents,
        environment: @escaping @MainActor () -> [String] = HighlightLabEnvironment.lines
    ) {
        self.service = service
        self.storage = storage
        self.environment = environment
        state = HighlightLabBenchmarkUiState(
            engines: service.engines,
            dataSets: service.dataSets,
            engineOrder: service.engines.map(\.id)
        )
        if let last = storage.loadLatest() {
            state.report = last
            state.tables = last.tables
        }
    }

    func setMode(_ mode: HighlightLabBenchmarkMode) {
        state.mode = mode
    }

    func toggleDataSet(_ id: String) {
        if state.selectedDataSetIDs.contains(id) {
            state.selectedDataSetIDs.remove(id)
        } else {
            state.selectedDataSetIDs.insert(id)
        }
    }

    /// Adds an engine at the end of the order, or takes it out.
    func toggleEngine(_ id: String) {
        if let index = state.engineOrder.firstIndex(of: id) {
            state.engineOrder.remove(at: index)
        } else {
            state.engineOrder.append(id)
        }
    }

    func reverseEngines() {
        state.engineOrder.reverse()
    }

    func start() {
        guard state.canRun else { return }
        let plan: HighlightLabPlan =
            switch state.mode {
            case .quick: .quick
            case .full: .full
            case .selected: .dataSets(state.dataSets.map(\.id).filter(state.selectedDataSetIDs.contains))
            }
        let names = state.engineOrder.compactMap { id in state.engines.first { $0.id == id }?.name }
        let label = state.mode == .selected ? "\(state.selectedDataSetIDs.count) data sets" : state.mode.rawValue
        let request = HighlightLabBenchmarkRequest(
            plan: plan,
            engineIDs: state.engineOrder,
            title: "Highlighting Lab run: \(label) (\(names.joined(separator: ", ")))",
            environment: environment()
        )
        runID += 1
        let current = runID
        state.errorMessage = nil
        state.phase = .running(nil)
        state.tables = nil
        let run = service.runBenchmark(request) { progress in
            Task { @MainActor in self.receive(progress, runID: current) }
        }
        self.run = run
        Task { await finish(run, runID: current, label: label) }
    }

    func stop() {
        guard case .running = state.phase else { return }
        state.phase = .stopping
        run?.stop()
    }

    private func receive(_ progress: HighlightLabProgress, runID: Int) {
        guard runID == self.runID, case .running = state.phase else { return }
        state.phase = .running(progress)
        state.tables = progress.tables
    }

    private func finish(_ run: any HighlightLabBenchmarkRun, runID: Int, label: String) async {
        let report = await run.report()
        guard runID == self.runID else { return }
        let stopped = state.phase == .stopping
        self.run = nil
        state.phase = .idle
        state.report = report
        state.tables = report.tables
        do {
            state.savedFiles = try storage.save(report, label: stopped ? "\(label)-stopped" : label)
        } catch {
            state.errorMessage = "Could not save the report: \(error.localizedDescription)"
        }
    }
}
#endif
