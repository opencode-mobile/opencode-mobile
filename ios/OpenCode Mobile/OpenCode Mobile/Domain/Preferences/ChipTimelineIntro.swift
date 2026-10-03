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

    /// Starting Chip timeline value, settled once per install: a fresh install
    /// starts on it; an install that already had the standard timeline keeps it
    /// and is offered the intro instead.
    static func startingChipTimeline(existingInstall: Bool) -> Bool {
        !existingInstall
    }

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

    /// Visible tool calls in the last finished turn: the assistant messages after
    /// the user message that started it. User rows after the last assistant message
    /// (queued or steered follow-ups) have no reply yet and do not start a turn.
    static func lastTurnToolCalls(_ messages: [Message]) -> Int {
        guard let lastAssistantIndex = messages.lastIndex(where: { $0.role == .assistant }) else { return 0 }
        let answered = messages[...lastAssistantIndex]
        let turnStart = answered.lastIndex(where: { $0.role == .user })
        let after = turnStart.map { answered[($0 + 1)...] } ?? answered
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
