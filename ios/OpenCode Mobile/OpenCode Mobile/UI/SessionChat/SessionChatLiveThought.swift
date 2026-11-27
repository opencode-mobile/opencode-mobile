import Foundation

/// The thought the thought sheet shows. Resolved from the loaded messages on every publish, so
/// the sheet follows the text while it streams and settles in place when it ends (#1118).
nonisolated struct SessionChatThoughtSheetState: Equatable, Sendable {
    let partID: String
    /// Display text: `[REDACTED]` removed and trimmed.
    let text: String
    let isStreaming: Bool
    /// How long a settled thought took; nil while streaming or when the server sent no times.
    let durationMs: Int64?
}

/// Rules for the turn's live thought: which thought is streaming now, and what the sheet shows.
nonisolated enum SessionChatLiveThought {
    /// The id of the thought streaming at the end of the current turn, or nil. Only a thought
    /// that is the turn's newest visible part counts: once a tool, text or file follows it,
    /// the turn has moved on even if the server has not closed the thought yet.
    static func streamingPartID(in messages: [Message]) -> String? {
        for message in currentTurnAssistants(messages).reversed() {
            for part in message.parts.reversed() {
                switch part {
                case let .reasoning(reasoning):
                    guard ThoughtPartChrome.isVisible(reasoning) else { continue }
                    return reasoning.isStreaming ? reasoning.id : nil
                case let .text(text):
                    if text.isDisplayable, text.isStreaming || !text.text.isEmpty { return nil }
                case let .tool(tool):
                    if ToolPartIdentity.isVisibleOnTimeline(tool) { return nil }
                case .file:
                    return nil
                case .compaction, .notice:
                    continue
                }
            }
        }
        return nil
    }

    /// The sheet for the thought `partID`, wherever it is in the loaded messages; nil once it
    /// is gone (reverted, another session) or blank.
    static func sheet(partID: String, in messages: [Message]) -> SessionChatThoughtSheetState? {
        for message in messages.reversed() {
            for case let .reasoning(reasoning) in message.parts where reasoning.id == partID {
                let text = ThoughtPartChrome.displayText(reasoning)
                guard !text.isEmpty else { return nil }
                return SessionChatThoughtSheetState(
                    partID: partID,
                    text: text,
                    isStreaming: reasoning.isStreaming,
                    durationMs: reasoning.isStreaming
                        ? nil
                        : durationMs(start: reasoning.timeStartEpochMs, end: reasoning.timeEndEpochMs)
                )
            }
        }
        return nil
    }

    /// Assistant replies after the newest sent prompt. A prompt still waiting for pickup has
    /// not started a turn, so the reply before it is still the current one.
    private static func currentTurnAssistants(_ messages: [Message]) -> [Message] {
        let lastPrompt = messages.lastIndex { $0.role == .user && !$0.isWaitingForPickup }
        let after = lastPrompt.map { messages[($0 + 1)...] } ?? messages[...]
        return after.filter { $0.role == .assistant }
    }

    private static func durationMs(start: Int64?, end: Int64?) -> Int64? {
        guard let start, let end, end >= start else { return nil }
        return end - start
    }
}

extension SessionChatThoughtSheetState {
    /// The standard thought sheet. Its subline reads "Thinking…" while the thought streams and
    /// the duration once it settles.
    var detailTarget: SessionChatToolDetailTarget {
        SessionChatStepTapActions.thoughtDetail(
            text: text,
            durationLabel: isStreaming
                ? OpenCodeActivityText.thinkingEllipsis
                : SessionChatTimelineBlocks.thoughtDurationLabel(durationMs)
        )
    }
}
