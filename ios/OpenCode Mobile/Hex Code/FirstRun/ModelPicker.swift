import Foundation
import OpenCodeKit

/// The model every prompt carries: provider, model, and a reasoning level (`nil` is Default).
struct ModelChoice: Codable, Equatable, Hashable, Sendable {
    var providerID: String
    var modelID: String
    var variant: String?
}

/// Result of the one-line test prompt for one model and reasoning level.
enum ModelTestState: Equatable, Sendable {
    case notRun
    case testing
    case passed(seconds: Double)
    case failed(String)
}

/// One group of rows on the model page: a provider, or every match while searching.
struct ModelPickerSection: Equatable, Identifiable {
    /// Provider label; `nil` for the flat list shown while searching.
    let title: String?
    let rows: [ModelPickerRow]

    var id: String { title ?? "search" }
}

struct ModelPickerRow: Equatable, Identifiable {
    let option: ModelSelectionOption
    let isServerDefault: Bool
    let isSelected: Bool
    /// Provider shown under the name while searching, since rows are no longer grouped.
    let providerCaption: String?

    var id: String { option.id }
}

/// The model page: search, provider groups, reasoning level, and the test that gates Start.
/// A test result belongs to the exact model and level it ran with, so changing either clears it.
struct ModelPickerState: Equatable {
    static let defaultVariant = "Default"

    let catalog: ModelSelectionCatalog
    var query = ""
    private(set) var choice: ModelChoice?
    private(set) var testedChoice: ModelChoice?
    private(set) var testState: ModelTestState = .notRun

    /// Starts on `saved` when it is still in the catalog, else on the server's default, else the first model.
    init(catalog: ModelSelectionCatalog, saved: ModelChoice? = nil) {
        self.catalog = catalog
        if let saved, let option = catalog.option(providerID: saved.providerID, modelID: saved.modelID) {
            let variant = saved.variant.flatMap { option.variants.contains($0) ? $0 : nil }
            choice = ModelChoice(providerID: option.providerID, modelID: option.modelID, variant: variant)
        } else if let option = catalog.serverDefault ?? catalog.options.first {
            choice = ModelChoice(providerID: option.providerID, modelID: option.modelID, variant: nil)
        }
    }

    var selectedOption: ModelSelectionOption? {
        choice.flatMap { catalog.option(providerID: $0.providerID, modelID: $0.modelID) }
    }

    /// "Default" plus the chosen model's own levels; empty when the model has none.
    var reasoningLevels: [String] {
        guard let option = selectedOption, !option.variants.isEmpty else { return [] }
        return [Self.defaultVariant] + option.variants
    }

    /// The level shown as selected: "Default" or one of the model's variants.
    var selectedLevel: String { choice?.variant ?? Self.defaultVariant }

    /// The test result for the current choice. A result for another choice reads as not run.
    var currentTest: ModelTestState {
        guard let choice, choice == testedChoice else { return .notRun }
        return testState
    }

    var canStart: Bool {
        if case .passed = currentTest { return true }
        return false
    }

    var isTesting: Bool { currentTest == .testing }

    var sections: [ModelPickerSection] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let matches = catalog.options.filter { Self.matches($0, query: trimmed) }
        if !trimmed.isEmpty {
            guard !matches.isEmpty else { return [] }
            return [ModelPickerSection(title: nil, rows: matches.map { row($0, caption: $0.providerLabel) })]
        }
        var order: [String] = []
        var grouped: [String: [ModelSelectionOption]] = [:]
        for option in matches {
            if grouped[option.providerID] == nil { order.append(option.providerID) }
            grouped[option.providerID, default: []].append(option)
        }
        return order.compactMap { providerID in
            guard let options = grouped[providerID], let first = options.first else { return nil }
            return ModelPickerSection(title: first.providerLabel, rows: options.map { row($0, caption: nil) })
        }
    }

    static func matches(_ option: ModelSelectionOption, query: String) -> Bool {
        guard !query.isEmpty else { return true }
        return [option.modelLabel, option.modelName, option.modelID, option.providerLabel, option.providerID]
            .contains { $0.localizedCaseInsensitiveContains(query) }
    }

    mutating func select(_ option: ModelSelectionOption) {
        let next = ModelChoice(providerID: option.providerID, modelID: option.modelID, variant: nil)
        guard next.providerID != choice?.providerID || next.modelID != choice?.modelID else { return }
        choice = next
    }

    /// Picks a level by its shown name; "Default" means no variant.
    mutating func selectLevel(_ level: String) {
        guard var next = choice else { return }
        next.variant = level == Self.defaultVariant ? nil : level
        choice = next
    }

    /// Marks the current choice as testing and returns it, or `nil` when a test is already running.
    mutating func beginTest() -> ModelChoice? {
        guard let choice, !isTesting else { return nil }
        testedChoice = choice
        testState = .testing
        return choice
    }

    /// Records a finished test. A result for a choice that is no longer the one under test is dropped.
    mutating func finishTest(_ tested: ModelChoice, result: ModelTestState) {
        guard tested == testedChoice, testState == .testing else { return }
        testState = result
    }

    private func row(_ option: ModelSelectionOption, caption: String?) -> ModelPickerRow {
        ModelPickerRow(
            option: option,
            isServerDefault: option == catalog.serverDefault,
            isSelected: option.providerID == choice?.providerID && option.modelID == choice?.modelID,
            providerCaption: caption
        )
    }
}
