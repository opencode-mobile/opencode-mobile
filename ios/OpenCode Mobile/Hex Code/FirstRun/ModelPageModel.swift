import Foundation
import Observation
import OpenCodeKit

/// The model page's Screen Model, shared by first run (ends in Start) and Settings (ends in Save):
/// loads the server's catalog for the project and runs the one-line test.
@MainActor
@Observable
final class ModelPageModel {
    enum Phase: Equatable {
        case loading
        case loaded(ModelPickerState)
        case failed
    }

    private(set) var phase: Phase = .loading

    @ObservationIgnored private let repository: any ModelRepository
    @ObservationIgnored private let tester: any ModelTesting
    @ObservationIgnored private let directory: String
    @ObservationIgnored private let saved: ModelChoice?
    @ObservationIgnored private var testTask: Task<Void, Never>?

    init(repository: any ModelRepository, tester: any ModelTesting, directory: String, saved: ModelChoice? = nil) {
        self.repository = repository
        self.tester = tester
        self.directory = directory
        self.saved = saved
    }

    var picker: ModelPickerState? {
        if case let .loaded(picker) = phase { return picker }
        return nil
    }

    /// The choice to keep, once it passed its test.
    var passedChoice: ModelChoice? {
        guard let picker, picker.canStart else { return nil }
        return picker.choice
    }

    /// The chosen model's name, as the server lists it.
    var selectedModelName: String? { picker?.selectedOption?.modelLabel }

    func load() async {
        phase = .loading
        do {
            let catalog = try await repository.listModelOptions(directory: directory)
            phase = catalog.isEmpty ? .failed : .loaded(ModelPickerState(catalog: catalog, saved: saved))
        } catch {
            phase = .failed
        }
    }

    func setQuery(_ query: String) { update { $0.query = query } }

    func select(_ option: ModelSelectionOption) { update { $0.select(option) } }

    func selectLevel(_ level: String) { update { $0.selectLevel(level) } }

    /// Sends the one-line test prompt with the current model and level.
    func test() {
        guard case var .loaded(picker) = phase, let choice = picker.beginTest() else { return }
        phase = .loaded(picker)
        let tester = tester
        testTask = Task { [weak self] in
            let result = await tester.test(choice)
            self?.update { $0.finishTest(choice, result: result) }
        }
    }

    func cancel() { testTask?.cancel() }

    private func update(_ change: (inout ModelPickerState) -> Void) {
        guard case var .loaded(picker) = phase else { return }
        change(&picker)
        phase = .loaded(picker)
    }
}
