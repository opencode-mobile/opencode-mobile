import Foundation

nonisolated enum DiagnosticLogExportWindow: Int, CaseIterable, Sendable {
    case fiveMinutes = 5
    case fifteenMinutes = 15
    case sixtyMinutes = 60

    static let `default` = fifteenMinutes

    var minutes: Int { rawValue }
    var milliseconds: Int64 { Int64(rawValue) * 60 * 1_000 }
    var label: String { "Last \(rawValue) minutes" }
}
