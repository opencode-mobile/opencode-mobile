import Foundation

public nonisolated enum DiagnosticLogExportWindow: Int, CaseIterable, Sendable {
    case fiveMinutes = 5
    case fifteenMinutes = 15
    case sixtyMinutes = 60

    public static let `default` = fifteenMinutes

    public var minutes: Int { rawValue }
    public var milliseconds: Int64 { Int64(rawValue) * 60 * 1_000 }
    public var label: String { "Last \(rawValue) minutes" }
}
