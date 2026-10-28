import Foundation

/// What a timeline notice row shows: label, optional value, and whether it is a move.
nonisolated struct TimelineNoticePresentation: Equatable, Sendable {
    let isMove: Bool
    let label: String
    let value: String?
}

/// Model display names from the composer's model catalog, for model-switch notices.
nonisolated struct TimelineNoticeModelNames: Equatable, Sendable {
    private let byOptionID: [String: String]
    private let byModelID: [String: String]

    static let empty = TimelineNoticeModelNames(byOptionID: [:], byModelID: [:])

    private init(byOptionID: [String: String], byModelID: [String: String]) {
        self.byOptionID = byOptionID
        self.byModelID = byModelID
    }

    init(options: [ModelSelectionOption]) {
        var byOptionID: [String: String] = [:]
        var byModelID: [String: String] = [:]
        for option in options where !option.modelName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            byOptionID[option.id] = option.modelName
            if byModelID[option.modelID] == nil { byModelID[option.modelID] = option.modelName }
        }
        self.init(byOptionID: byOptionID, byModelID: byModelID)
    }

    /// The catalog name for this provider and model; a notice without a provider
    /// matches the first model with that id.
    func name(for model: TimelineNoticeModel) -> String? {
        if let providerID = model.providerID {
            return byOptionID["\(providerID):\(model.modelID)"]
        }
        return byModelID[model.modelID]
    }
}

/// Copy for timeline notices, matching official opencode (`ui.sessionTimeline.notice.*`).
nonisolated enum TimelineNoticeCopy {
    static let movedTo = "Moved to"
    static let agentChanged = "Agent changed"
    static let switchedTo = "Switched to"
    static let switchedVariantTo = "Switched variant to"
    static let switchedVariantToDefault = "Switched variant to Default"
    static let instructionsUpdated = "Instructions updated"
    static let restart = "Continuing after restart"

    static func presentation(
        for notice: TimelineNotice,
        modelNames: TimelineNoticeModelNames = .empty
    ) -> TimelineNoticePresentation {
        switch notice.kind {
        case let .moved(directory):
            return TimelineNoticePresentation(isMove: true, label: movedTo, value: directory)
        case let .agentChanged(agent, previous):
            let current = TitleCaseIdentifier.displayName(for: agent)
            let value = previous.map { "\(TitleCaseIdentifier.displayName(for: $0)) \u{2192} \(current)" } ?? current
            return TimelineNoticePresentation(isMove: false, label: agentChanged, value: value)
        case let .modelChanged(model, previous):
            let variantOnly = previous.map { $0.providerID == model.providerID && $0.modelID == model.modelID } ?? false
            guard variantOnly else {
                return TimelineNoticePresentation(
                    isMove: false,
                    label: switchedTo,
                    value: modelNames.name(for: model) ?? model.modelID
                )
            }
            guard let variant = model.variant, variant.caseInsensitiveCompare("default") != .orderedSame else {
                return TimelineNoticePresentation(isMove: false, label: switchedVariantToDefault, value: nil)
            }
            return TimelineNoticePresentation(isMove: false, label: switchedVariantTo, value: variant)
        case let .instructionsUpdated(sources):
            return TimelineNoticePresentation(
                isMove: false,
                label: instructionsUpdated,
                value: sources.isEmpty ? nil : sources.joined(separator: ", ")
            )
        case let .systemNote(description):
            return TimelineNoticePresentation(isMove: false, label: description, value: nil)
        case .restart:
            return TimelineNoticePresentation(isMove: false, label: restart, value: nil)
        }
    }
}
