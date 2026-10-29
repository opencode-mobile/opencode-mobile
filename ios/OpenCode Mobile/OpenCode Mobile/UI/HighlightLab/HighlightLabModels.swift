#if HIGHLIGHT_LAB
import Foundation

/// What the Highlighting Lab (#872) opens: a bundled data set by id, or text the Lab brought itself.
nonisolated enum HighlightLabInput: Hashable, Sendable, Identifiable {
    case catalog(id: String)
    case custom(HighlightLabCustomText)

    var id: String {
        switch self {
        case let .catalog(id): id
        case let .custom(text): "custom/\(text.id)"
        }
    }
}

/// Pasted text or a File Viewer file, measured like a worst case.
nonisolated struct HighlightLabCustomText: Hashable, Sendable, Identifiable {
    let id: String
    let displayName: String
    let language: String
    let source: String
}

nonisolated enum HighlightLabDataKind: String, CaseIterable, Hashable, Sendable {
    case typicalCode
    case chatFences
    case worstCase
    case custom

    var label: String {
        switch self {
        case .typicalCode: "Typical code"
        case .chatFences: "Chat fences"
        case .worstCase: "Worst case"
        case .custom: "Custom"
        }
    }
}

/// A size class as the catalog browses it; `label` is "2K", "1 MB", or "Chat fences".
nonisolated struct HighlightLabSizeClass: Hashable, Sendable, Identifiable {
    let id: String
    let label: String
}

nonisolated struct HighlightLabDataSetInfo: Hashable, Sendable, Identifiable {
    let id: String
    let displayName: String
    let language: String
    let sizeClass: HighlightLabSizeClass
    let kind: HighlightLabDataKind
}

nonisolated struct HighlightLabEngineOption: Hashable, Sendable, Identifiable {
    let id: String
    let name: String
}

/// A colored range in UTF-16 offsets; `role` is the engine's role name, such as "Keyword".
nonisolated struct HighlightLabSpan: Hashable, Sendable {
    let start: Int
    let end: Int
    let role: String
}

/// One timed engine call, for the timing strip.
nonisolated struct HighlightLabTiming: Equatable, Sendable {
    let engineName: String
    let elapsedMs: Double
    let characters: Int
    /// How the call ended against its limit, for example "Completed" or "Abandoned".
    let status: String
    /// The engine's outcome, for example "Highlighted"; nil when it never returned.
    let outcome: String?
    let spanCount: Int
    /// The call ran on a fresh engine instance (lexer setup and first-call warm-up included).
    let cold: Bool

    /// Nil unless the call completed within its limit; a cancelled call did not highlight it all.
    var charsPerMs: Double? {
        status == "Completed" && elapsedMs > 0 ? Double(characters) / elapsedMs : nil
    }

    /// The call time; "> N ms" when the guard gave up on a call that never returned.
    var elapsedLabel: String {
        let time = HighlightLabFormat.milliseconds(elapsedMs)
        return status == "Abandoned" || status == "ForceStopped" ? "> \(time)" : time
    }
}

/// An input's text, generated once and shown plain before any engine runs.
nonisolated struct HighlightLabText: Sendable {
    let language: String
    let source: String
}

nonisolated struct HighlightLabProbeResult: Sendable {
    let source: String
    let language: String
    /// Nil when the call did not return spans (gave up on, or threw).
    let spans: [HighlightLabSpan]?
    let timing: HighlightLabTiming
}

nonisolated enum HighlightLabPlan: Hashable, Sendable {
    case quick
    case full
    case dataSets([String])
}

nonisolated struct HighlightLabBenchmarkRequest: Sendable {
    let plan: HighlightLabPlan
    let engineIDs: [String]
    let title: String
    let environment: [String]
}

/// Sent before and after each case and engine.
nonisolated struct HighlightLabProgress: Equatable, Sendable {
    /// One-based case number and the case count.
    let caseNumber: Int
    let caseCount: Int
    let caseTitle: String
    let engineName: String
    /// Steps finished so far (cases times engines) and their total.
    let completedSteps: Int
    let totalSteps: Int
    let tables: HighlightLabReportTables
}

/// A report as tables, ready to show and to keep in app storage.
nonisolated struct HighlightLabReportTables: Codable, Equatable, Sendable {
    nonisolated struct Table: Codable, Equatable, Sendable, Identifiable {
        let id: String
        let title: String
        let headers: [String]
        let rows: [Row]
    }

    nonisolated struct Row: Codable, Equatable, Sendable, Identifiable {
        let id: String
        let cells: [String]
        /// The case status, for example "Completed" or "IgnoredCancel".
        let status: String
    }

    let title: String
    let engines: [String]
    let complete: Bool
    let sections: [Table]
    /// Baseline against each other engine; nil for a single-engine run.
    let comparison: Table?
}

nonisolated struct HighlightLabReport: Equatable, Sendable {
    let tables: HighlightLabReportTables
    let markdown: String
    let json: String
}

/// Formatting shared by the Lab screens.
nonisolated enum HighlightLabFormat {
    static func milliseconds(_ value: Double) -> String {
        value >= 100 ? String(format: "%.0f ms", value) : String(format: "%.2f ms", value)
    }

    static func rate(_ value: Double?) -> String {
        guard let value else { return "–" }
        return value >= 100 ? String(format: "%.0f", value) : String(format: "%.1f", value)
    }

    static func count(_ value: Int) -> String {
        value.formatted(.number.grouping(.automatic))
    }

    static func percent(_ value: Double) -> String {
        String(format: "%.1f%%", value * 100)
    }
}
#endif
