import Foundation

/// Status-chrome copy for Session Compact (OPE-116, prototype B).
enum SessionCompactChrome {
    static let statusLabel = "Compacting…"
    static let fallbackSuccess = "Session compacted."
    static let failure = "Couldn't compact session context."

    static func successMessage(beforeFraction: Float?, afterFraction: Float?) -> String {
        guard let beforeFraction, let afterFraction else { return fallbackSuccess }
        let before = Int(beforeFraction * 100)
        let after = Int(afterFraction * 100)
        return "Context compacted · \(before)% → \(after)%"
    }
}
