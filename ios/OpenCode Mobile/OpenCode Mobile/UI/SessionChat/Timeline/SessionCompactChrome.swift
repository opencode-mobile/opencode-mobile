import Foundation

/// Status-chrome copy for Session Compact (OPE-116, prototype B).
nonisolated enum SessionCompactChrome {
    static let statusLabel = "Compacting…"
    /// Timeline marker for a server compaction item; the summary opens from it.
    static let markerTitle = "Context compacted"
    static let markerAutoSubtitle = "Automatic"
    static let markerToolName = "compaction"
    static let fallbackSuccess = "Session compacted."
    static let failure = "Couldn't compact session context."

    static func successMessage(beforeFraction: Float?, afterFraction: Float?) -> String {
        guard let beforeFraction, let afterFraction else { return fallbackSuccess }
        let before = Int(beforeFraction * 100)
        let after = Int(afterFraction * 100)
        return "Context compacted · \(before)% → \(after)%"
    }
}
