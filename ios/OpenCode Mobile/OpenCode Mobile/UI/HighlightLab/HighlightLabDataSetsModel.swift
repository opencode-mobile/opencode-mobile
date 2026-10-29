#if HIGHLIGHT_LAB
import Foundation
import Observation

nonisolated struct HighlightLabDataSetRow: Equatable, Identifiable, Sendable {
    let id: String
    let displayName: String
    let language: String
    let sizeLabel: String
    let kindLabel: String
    /// Nil until the texts were generated once.
    let characters: Int?

    /// The title already names the language and size.
    var subtitle: String {
        let size = characters.map { "\(HighlightLabFormat.count($0)) characters" } ?? "Counting characters…"
        return "\(kindLabel) · \(size)"
    }
}

nonisolated struct HighlightLabDataSetsUiState: Equatable, Sendable {
    let languages: [String]
    let sizeClasses: [HighlightLabSizeClass]
    let kinds: [HighlightLabDataKind]
    var language: String?
    var sizeClassID: String?
    var kind: HighlightLabDataKind?
    var rows: [HighlightLabDataSetRow] = []
}

/// Screen Model for the Lab's data set browser.
@MainActor
@Observable
final class HighlightLabDataSetsModel {
    private(set) var state: HighlightLabDataSetsUiState

    @ObservationIgnored private let service: any HighlightLabService
    @ObservationIgnored private var counts: [String: Int] = [:]
    @ObservationIgnored private var started = false

    init(service: any HighlightLabService) {
        self.service = service
        state = HighlightLabDataSetsUiState(
            languages: service.languages,
            sizeClasses: service.sizeClasses,
            kinds: [.typicalCode, .chatFences, .worstCase]
        )
        rebuild()
    }

    /// Counts characters once; rows show the counts when they arrive.
    func start() async {
        guard !started else { return }
        started = true
        counts = await service.characterCounts()
        rebuild()
    }

    func setLanguage(_ language: String?) {
        state.language = language
        rebuild()
    }

    func setSizeClass(_ id: String?) {
        state.sizeClassID = id
        rebuild()
    }

    func setKind(_ kind: HighlightLabDataKind?) {
        state.kind = kind
        rebuild()
    }

    private func rebuild() {
        state.rows = service.dataSets
            .filter { dataSet in
                (state.language == nil || dataSet.language == state.language)
                    && (state.sizeClassID == nil || dataSet.sizeClass.id == state.sizeClassID)
                    && (state.kind == nil || dataSet.kind == state.kind)
            }
            .map { dataSet in
                HighlightLabDataSetRow(
                    id: dataSet.id,
                    displayName: dataSet.displayName,
                    language: dataSet.language,
                    sizeLabel: dataSet.sizeClass.label,
                    kindLabel: dataSet.kind.label,
                    characters: counts[dataSet.id]
                )
            }
    }
}
#endif
