import Foundation

/// Status-chrome copy for Session Compact (OPE-116, prototype B).
nonisolated enum SessionCompactChrome {
    /// For the working indicator and snackbars, whose labels are plain strings.
    static var statusLabel: String {
        String(localized: "session_chat.compact.status", defaultValue: "Compacting…")
    }

    /// The running compaction card's title.
    static let markerStatus = TimelineText.app("session_chat.compact.status", "Compacting…")
    /// Timeline marker for a server compaction item; the summary opens from it.
    static let markerTitle = TimelineText.app("timeline.compaction.title", "Context compacted")
    static let markerAutoSubtitle = TimelineText.app("timeline.compaction.automatic", "Automatic")
    static let markerToolName = "compaction"

    static var fallbackSuccess: String {
        String(localized: "session_chat.compact.success", defaultValue: "Session compacted.")
    }

    static var failure: String {
        String(localized: "session_chat.compact.failure", defaultValue: "Couldn't compact session context.")
    }

    static func successMessage(beforeFraction: Float?, afterFraction: Float?) -> String {
        // 0% means usage is unknown (no token counts yet), not an empty context.
        guard let beforeFraction, let afterFraction, beforeFraction > 0, afterFraction > 0 else {
            return fallbackSuccess
        }
        let before = Int(beforeFraction * 100)
        let after = Int(afterFraction * 100)
        return String(
            localized: "session_chat.compact.success_usage",
            defaultValue: "Context compacted · \(before)% → \(after)%"
        )
    }
}
