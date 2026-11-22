import Foundation

/// A user prompt sent with steer delivery during a run, as the timeline shows it.
/// Queued prompts never get one: they stay out of the timeline.
nonisolated struct SessionChatSteer: Equatable, Hashable, Sendable {
    /// Waiting for the next step; the server has not delivered it yet.
    var isPending: Bool
    /// First of a run of consecutive steers, so it draws the shared label.
    var showsLabel: Bool
}

/// Whether the current busy period has produced assistant output. Between two steps the
/// newest assistant message is finished, so only this tells a steer waiting for the next
/// step from an idle prompt, which v2 also enqueues as a steer but delivers before any output.
nonisolated struct SteerRunTracker: Equatable, Sendable {
    private var hasResolvedStatus = false
    private var isBusy = false
    private var baselineAssistantID: String?

    mutating func update(isBusy: Bool, isResolved: Bool, messages: [Message]) {
        guard isResolved else { return }
        if isBusy, !self.isBusy {
            // Opening a session mid-run: everything loaded may belong to this run.
            baselineAssistantID = hasResolvedStatus ? Self.newestAssistantID(messages) : nil
        }
        hasResolvedStatus = true
        self.isBusy = isBusy
    }

    func hasOutput(_ messages: [Message]) -> Bool {
        guard isBusy, let newest = Self.newestAssistantID(messages) else { return false }
        return newest != baselineAssistantID
    }

    private static func newestAssistantID(_ messages: [Message]) -> String? {
        messages.last(where: { $0.role == .assistant })?.id
    }
}
