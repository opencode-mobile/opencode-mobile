import Foundation

/// One-time Chip timeline intro sheet (OPE-270): when Session Chat offers it and
/// what an answer stores. Storage lives in `SessionChatDisplayPreferences`.
nonisolated enum ChipTimelineIntro {
    /// A finished turn must have used at least this many tool calls.
    static let minToolCalls = 8

    /// Later hides the sheet for this long; a second Later ends the intro.
    static let snoozeMs: Int64 = 3 * 24 * 60 * 60 * 1000

    /// Pause after the turn finishes so the user sees the result first.
    static let settleDelayMs: Int64 = 1_500

    static func shouldShow(
        chipTimelineAvailable: Bool,
        chipTimelineOn: Bool,
        introDone: Bool,
        snoozedUntilMs: Int64?,
        nowMs: Int64,
        turnToolCalls: Int
    ) -> Bool {
        chipTimelineAvailable
            && !chipTimelineOn
            && !introDone
            && (snoozedUntilMs.map { $0 <= nowMs } ?? true)
            && turnToolCalls >= minToolCalls
    }

    /// Snooze end for a Later answer, or nil when this Later ends the intro.
    static func laterSnoozeUntil(snoozedUntilMs: Int64?, nowMs: Int64) -> Int64? {
        snoozedUntilMs == nil ? nowMs + snoozeMs : nil
    }

    /// Visible tool calls in the assistant messages after the last user message.
    static func lastTurnToolCalls(_ messages: [Message]) -> Int {
        let lastUserIndex = messages.lastIndex(where: { $0.role == .user })
        let after = lastUserIndex.map { messages.dropFirst($0 + 1) } ?? messages[...]
        return after
            .filter { $0.role == .assistant }
            .reduce(0) { total, message in
                total + message.parts.reduce(0) { count, part in
                    if case .tool(let tool) = part, ToolPartIdentity.isVisibleOnTimeline(tool) {
                        return count + 1
                    }
                    return count
                }
            }
    }
}
