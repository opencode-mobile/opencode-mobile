import Foundation

/// Activity card (#779): what `OpenCodeActivityCard` shows for one card block,
/// derived from product types only, so it can be tested without a view.
struct SessionChatActivityCardPresentation: Equatable {
    let state: OpenCodeActivityCardState
    let summary: String
    let agents: [OpenCodeActivityAgentRow]
    let current: OpenCodeActivityCurrentRow?
    let idleText: String?

    /// `childActions`: current action of running child sessions by child session id.
    init(card: SessionChatActivityCardBlock, childActions: [String: String], awaitingUser: Bool) {
        guard card.isLive else {
            state = .settled(
                worstStatus: card.worstStatus == .failed ? .failed : .completed,
                stepCount: card.steps.count
            )
            summary = SessionChatActivityCardBlock.usedToolNames(card.steps).joined(separator: ", ")
            agents = []
            current = nil
            idleText = nil
            return
        }
        state = .live(startedAtMs: ToolStartTime.normalizeEpochMs(card.startedAtMs))
        summary = SessionChatActivityCardBlock.summary(card.steps)
        let runningChildren = card.runningChildren
        agents = runningChildren.map { step in
            let childID = step.childSession?.childSessionId
            return OpenCodeActivityAgentRow(
                id: step.id,
                agentName: step.agentDisplayName,
                action: childID.flatMap { childActions[$0] } ?? step.childSession?.description ?? step.detail,
                startedAtMs: ToolStartTime.normalizeEpochMs(step.startedAtMs)
            )
        }
        if let latest = card.latestRunningTool {
            current = OpenCodeActivityCurrentRow(
                icon: latest.activityIcon,
                title: latest.runningTitle,
                extraRunning: card.runningTools.count - 1,
                startedAtMs: ToolStartTime.normalizeEpochMs(latest.startedAtMs)
            )
            idleText = nil
        } else {
            current = nil
            idleText = SessionChatActivityCardBlock.idleText(
                hasRunningChildren: !runningChildren.isEmpty,
                awaitingUser: awaitingUser
            )
        }
    }

    /// The card the activity sheet shows: looked up by id in the current rows
    /// on every render, so the sheet follows the card while it streams and
    /// closes when it is gone. Returns the card and the id of its row.
    static func card(id: String, in rows: [SessionChatRow]) -> (card: SessionChatActivityCardBlock, rowID: String)? {
        for row in rows.reversed() where row.role == .assistant {
            for case .activityCard(_, let card) in row.blocks where card.id == id {
                return (card, row.id)
            }
        }
        return nil
    }

    /// Current actions of the running child sessions on the live card, for the
    /// rows that need to repaint when one changes.
    static func childActions(
        in rows: [SessionChatRow],
        liveCardID: String?,
        activities: [String: SessionChatChildActivity]
    ) -> [String: String] {
        guard let liveCardID, !activities.isEmpty, let live = card(id: liveCardID, in: rows) else { return [:] }
        var actions: [String: String] = [:]
        for step in live.card.runningChildren {
            guard let id = step.childSession?.childSessionId,
                  let action = activities[id]?.currentAction
            else { continue }
            actions[id] = action
        }
        return actions
    }
}

extension SessionChatStep {
    var agentDisplayName: String {
        OpenCodeToolChip.displayAgentName(childSession?.agent ?? title)
    }

    var activityIcon: OpenCodeActivityLineIcon {
        switch family {
        case .read: .read
        case .search: .search
        case .fetch: .fetch
        case .edit: .edit
        case .command: .command
        case .thought: .thought
        case .agent: .agent
        case nil: card?.toolName.lowercased() == "skill" ? .skill : .other
        }
    }

    var activityStatus: OpenCodeActivityLineStatus {
        switch status {
        case .pending: .pending
        case .running: .running
        case .completed: .completed
        case .failed: isInterrupted ? .interrupted : .failed
        }
    }
}
