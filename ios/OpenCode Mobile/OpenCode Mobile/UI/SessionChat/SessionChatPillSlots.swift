import Foundation

/// Which bottom-left activity pill wins. Lower raw values come first.
enum SessionChatActivityPriority: Int, Comparable, CaseIterable {
    case needsInput
    case error
    case answerReady
    case running
    case queued
    /// Fades after `SessionChatPillSlots.finishedFadeSeconds`, so beside other work it only shows in the stack.
    case finished

    static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// One thing the bottom-left live activity slot can show. A new kind of live
/// work is a new case plus its `priority` entry.
enum SessionChatActivityItem: Equatable, Identifiable {
    /// A collapsed permission panel; tapping brings it back.
    case reviewPermission
    /// A collapsed question panel; tapping brings it back.
    case answerQuestion
    /// Subagents of this session at work: "3 subagents", or "general needs input · 2 running"
    /// while one waits. Tapping opens the Subagents panel.
    case subagents(SessionChatBackgroundWorkPill)
    /// The last turn failed. `rowID` is the timeline row to scroll to; `blockID` identifies the failure.
    case requestFailed(rowID: String, blockID: String)
    /// `/btw` side questions that failed; tapping asks them again.
    case sideQuestionsFailed(count: Int)
    /// `/btw` answers waiting to be read.
    case sideQuestionsReady(count: Int)
    /// `/btw` side questions still running.
    case sideQuestionsRunning(count: Int)
    /// Cached or catching-up messages waiting for the server.
    case syncing(label: String)
    /// Prompts waiting to be sent after the current turn.
    case queued(count: Int)
    /// Work that just finished, shown until it fades.
    case finished(id: String, label: String, finishedAt: Date)

    var id: String {
        switch self {
        case .reviewPermission: "review-permission"
        case .answerQuestion: "answer-question"
        case .subagents: "subagents"
        case let .requestFailed(_, blockID): "failed:\(blockID)"
        case .sideQuestionsFailed: "btw-failed"
        case .sideQuestionsReady: "btw-ready"
        case .sideQuestionsRunning: "btw-running"
        case .syncing: "syncing"
        case .queued: "queued"
        case let .finished(id, _, _): "finished:\(id)"
        }
    }

    var priority: SessionChatActivityPriority {
        switch self {
        case .reviewPermission, .answerQuestion: .needsInput
        case let .subagents(pill): pill.needsInput ? .needsInput : .running
        case .requestFailed, .sideQuestionsFailed: .error
        case .sideQuestionsReady: .answerReady
        case .sideQuestionsRunning, .syncing: .running
        case .queued: .queued
        case .finished: .finished
        }
    }
}

/// What the bottom-left slot draws: one pill, a "+N" button, and the stack "+N" opens.
struct SessionChatActivitySlot: Equatable {
    var primary: SessionChatActivityItem?
    /// The primary pill shrinks to its round mark (keyboard up, unless it needs input).
    var primaryCompact: Bool
    /// The rest, in priority order. Shown only while the stack is open.
    var overflow: [SessionChatActivityItem]
    /// The "+N" button. Hidden with the keyboard up.
    var showsOverflowButton: Bool

    static let empty = SessionChatActivitySlot(primary: nil, primaryCompact: false, overflow: [], showsOverflowButton: false)
}

/// A top-left context pill.
enum SessionChatContextItem: Equatable {
    case todos(completed: Int, total: Int)
    case branch(SessionChatBranchPillUiState)
}

/// The bottom-right navigation pill: a round ↓, or "↓ N new" once messages arrive below.
enum SessionChatNavigationPill: Equatable {
    case scrollToEnd(newCount: Int)
}

/// Pure rules for the three Session Chat corner slots.
enum SessionChatPillSlots {
    static let finishedFadeSeconds: TimeInterval = 3
    static let maxContextPills = 2

    /// Bottom left: highest priority first, the rest behind "+N". Finished items
    /// older than the fade drop out. With the keyboard up the pill goes compact
    /// unless it needs input, and "+N" hides.
    static func activity(
        items: [SessionChatActivityItem],
        keyboardVisible: Bool,
        now: Date
    ) -> SessionChatActivitySlot {
        let live = items.filter { !isFaded($0, now: now) }
        // Stable: equal priorities keep the caller's order.
        let sorted = live.enumerated()
            .sorted { lhs, rhs in
                lhs.element.priority == rhs.element.priority
                    ? lhs.offset < rhs.offset
                    : lhs.element.priority < rhs.element.priority
            }
            .map(\.element)
        guard let primary = sorted.first else { return .empty }
        let overflow = Array(sorted.dropFirst())
        return SessionChatActivitySlot(
            primary: primary,
            primaryCompact: keyboardVisible && primary.priority != .needsInput,
            overflow: keyboardVisible ? [] : overflow,
            showsOverflowButton: !keyboardVisible && !overflow.isEmpty
        )
    }

    static func isFaded(_ item: SessionChatActivityItem, now: Date) -> Bool {
        guard case let .finished(_, _, finishedAt) = item else { return false }
        return now.timeIntervalSince(finishedAt) >= finishedFadeSeconds
    }

    /// When the next finished item fades, so the host can resolve the slot again then.
    static func nextFade(items: [SessionChatActivityItem], now: Date) -> Date? {
        items.compactMap { item -> Date? in
            guard case let .finished(_, _, finishedAt) = item else { return nil }
            let fade = finishedAt.addingTimeInterval(finishedFadeSeconds)
            return fade > now ? fade : nil
        }
        .min()
    }

    /// Top left: the branch in the corner, then todos, at most two.
    static func context(
        todos: (completed: Int, total: Int)?,
        branch: SessionChatBranchPillUiState?
    ) -> [SessionChatContextItem] {
        var items: [SessionChatContextItem] = []
        if let branch {
            items.append(.branch(branch))
        }
        if let todos, todos.total > 0 {
            items.append(.todos(completed: min(max(todos.completed, 0), todos.total), total: todos.total))
        }
        return Array(items.prefix(maxContextPills))
    }

    /// The keyboard hides the top-left pills. Their row keeps its place, so the timeline's
    /// top inset does not change and content never slides under the top bar.
    static func showsContext(keyboardVisible: Bool) -> Bool {
        !keyboardVisible
    }

    /// Bottom right: empty at the end of the chat and with the keyboard up.
    static func navigation(
        awayFromEnd: Bool,
        newCount: Int,
        keyboardVisible: Bool
    ) -> SessionChatNavigationPill? {
        guard awayFromEnd, !keyboardVisible else { return nil }
        return .scrollToEnd(newCount: max(newCount, 0))
    }

    /// Rows that arrived after `baselineLastID`, the newest row when the reader left the end.
    /// Older history loaded above does not count.
    static func newMessageCount(rowIDs: [String], baselineLastID: String?) -> Int {
        guard let baselineLastID, let index = rowIDs.lastIndex(of: baselineLastID) else { return 0 }
        return rowIDs.count - index - 1
    }

    /// The failure that ended the last turn, if any. A user row after it, a
    /// neutral mark (a denied permission) or a live retry means no pill.
    static func latestFailure(
        in rows: [SessionChatRow],
        isRetrying: Bool
    ) -> (rowID: String, blockID: String)? {
        guard !isRetrying else { return nil }
        for row in rows.reversed() {
            if row.role == .user { return nil }
            for block in row.blocks.reversed() {
                if case let .failureLine(blockID, _, _, isNeutral, _, _, _, _) = block {
                    return isNeutral ? nil : (row.id, blockID)
                }
            }
        }
        return nil
    }
}
