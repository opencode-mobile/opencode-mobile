import Foundation

/// Which bottom-left activity pill wins. Lower raw values come first.
enum SessionChatActivityPriority: Int, Comparable, CaseIterable {
    case needsInput
    case error
    case answerReady
    case running
    case queued

    static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// One thing the bottom-left row reports. A new kind of live work is a new case plus its
/// `priority` entry. Requests, failures and a waiting subagent feed the turn pill; the rest
/// are secondary pills.
enum SessionChatActivityItem: Equatable, Identifiable {
    /// A collapsed permission panel; tapping brings it back.
    case reviewPermission
    /// A collapsed question panel; tapping brings it back.
    case answerQuestion
    /// Subagents and shells of this session at work: "3 subagents", "1 subagent · 2 shells",
    /// or "general needs input · 2 running" while a subagent waits. Tapping opens the waiting
    /// request, else the background work panel.
    case subagents(SessionChatBackgroundWorkPill)
    /// The last turn failed. `rowID` is the timeline row to scroll to; `blockID` identifies the failure.
    case requestFailed(rowID: String, blockID: String)
    /// `/btw` side questions that failed; tapping asks them again.
    case sideQuestionsFailed(count: Int)
    /// `/btw` answers waiting to be read.
    case sideQuestionsReady(count: Int)
    /// `/btw` side questions still running.
    case sideQuestionsRunning(count: Int)
    /// Prompts waiting to be sent after the current turn.
    case queued(count: Int)

    var id: String {
        switch self {
        case .reviewPermission: "review-permission"
        case .answerQuestion: "answer-question"
        case .subagents: "subagents"
        case let .requestFailed(_, blockID): "failed:\(blockID)"
        case .sideQuestionsFailed: "btw-failed"
        case .sideQuestionsReady: "btw-ready"
        case .sideQuestionsRunning: "btw-running"
        case .queued: "queued"
        }
    }

    var priority: SessionChatActivityPriority {
        switch self {
        case .reviewPermission, .answerQuestion: .needsInput
        case let .subagents(pill): pill.needsInput ? .needsInput : .running
        case .requestFailed, .sideQuestionsFailed: .error
        case .sideQuestionsReady: .answerReady
        case .sideQuestionsRunning: .running
        case .queued: .queued
        }
    }
}

/// What the bottom-left slot draws: one pill, a "+N" button, and the stack "+N" opens.
/// Away from the end everything folds into one round pill (`isCollapsed`) whose stack lists every item.
struct SessionChatActivitySlot: Equatable {
    var primary: SessionChatActivityItem?
    /// The primary pill shrinks to its round mark (keyboard up).
    var primaryCompact: Bool
    /// The rest, in priority order. Shown only while the stack is open.
    var overflow: [SessionChatActivityItem]
    /// The "+N" button. Hidden with the keyboard up and while collapsed.
    var showsOverflowButton: Bool
    /// The reader is away from the end: one round pill stands for the primary and the rest.
    var isCollapsed = false
    /// Every live item in priority order, primary first. Only the collapsed pill's stack lists them all.
    var allItems: [SessionChatActivityItem] = []

    static let empty = SessionChatActivitySlot(primary: nil, primaryCompact: false, overflow: [], showsOverflowButton: false)
}

/// The one turn pill that leads the bottom-left row in every timeline style. It is not an
/// activity item: it never counts toward "+N" and is never in the stack.
struct SessionChatTurnStatusSlot: Equatable {
    var phase: OpenCodeTurnStatusPillPhase
    /// Folded to its round mark: away from the end or with the keyboard up. Attention and
    /// failed never fold.
    var compact: Bool
    /// Fading out. The timeline already gives its row back, so the list glides down.
    var leaving: Bool
    /// What a tap opens; nil when the pill is only a status.
    var tapItem: SessionChatActivityItem? = nil
    /// Shows a close that dismisses `tapItem`.
    var isClosable = false
    var accessibilityLabel: String? = nil

    var reservesRow: Bool { !leaving }
}

/// Measured widths of the bottom pill row. A zero width means that pill is not shown.
struct SessionChatPillRowWidths: Equatable {
    var available: CGFloat
    var turn: CGFloat = 0
    var secondary: CGFloat = 0
    var overflow: CGFloat = 0
    /// The bottom-right pills, including their own leading spacing.
    var trailing: CGFloat = 0
    var spacing: CGFloat = 8
    /// A folded pill's round width.
    var compact: CGFloat = 36
}

/// What the bottom row folds so it fits.
struct SessionChatPillRowFold: Equatable {
    var turnCompact = false
    var secondaryCompact = false
    /// Set when a pinned turn pill still does not fit: its label truncates to this pill width.
    var turnMaxWidth: CGFloat? = nil
}

/// A top-left context pill.
enum SessionChatContextItem: Equatable {
    /// The session's `/goal`; first in the row.
    case goal(SessionChatGoalPillUiState)
    case todos(completed: Int, total: Int)
    case branch(SessionChatBranchPillUiState)
}

/// The bottom-right navigation pill: "↓ Scroll to end", or "↓ N new" once messages arrive below.
enum SessionChatNavigationPill: Equatable {
    case scrollToEnd(newCount: Int)

    var label: String {
        switch self {
        case let .scrollToEnd(newCount):
            newCount > 0
                ? String(localized: "session_chat.navigation.new_count", defaultValue: "\(newCount) new")
                : Self.scrollToEnd
        }
    }

    var accessibilityLabel: String {
        switch self {
        case let .scrollToEnd(newCount):
            newCount > 0
                ? String(
                    localized: "session_chat.navigation.scroll_to_end_new_messages",
                    defaultValue: "Scroll to end, \(newCount) new messages"
                )
                : Self.scrollToEnd
        }
    }

    private static var scrollToEnd: String {
        String(localized: "session_chat.navigation.scroll_to_end", defaultValue: "Scroll to end")
    }
}

/// Pure rules for the three Session Chat corner slots.
enum SessionChatPillSlots {
    static let maxContextPills = 3

    /// The secondary pills: everything the turn pill does not carry. A subagents item that
    /// needs input keeps only its still-running count.
    static func secondaryItems(_ items: [SessionChatActivityItem]) -> [SessionChatActivityItem] {
        items.compactMap { item in
            switch item {
            case .reviewPermission, .answerQuestion, .requestFailed:
                nil
            case let .subagents(pill):
                pill.secondary.map { .subagents($0) }
            default:
                item
            }
        }
    }

    /// Bottom left secondary pills: highest priority first, the rest behind "+N". With the
    /// keyboard up the pill goes compact and "+N" hides. Away from the end (keyboard down)
    /// they fold into one round pill.
    static func activity(
        items: [SessionChatActivityItem],
        keyboardVisible: Bool,
        awayFromEnd: Bool = false
    ) -> SessionChatActivitySlot {
        // Stable: equal priorities keep the caller's order.
        let sorted = secondaryItems(items).enumerated()
            .sorted { lhs, rhs in
                lhs.element.priority == rhs.element.priority
                    ? lhs.offset < rhs.offset
                    : lhs.element.priority < rhs.element.priority
            }
            .map(\.element)
        guard let primary = sorted.first else { return .empty }
        let overflow = Array(sorted.dropFirst())
        let collapsed = collapsesActivity(awayFromEnd: awayFromEnd, keyboardVisible: keyboardVisible)
        return SessionChatActivitySlot(
            primary: primary,
            primaryCompact: keyboardVisible,
            overflow: keyboardVisible ? [] : overflow,
            showsOverflowButton: !keyboardVisible && !collapsed && !overflow.isEmpty,
            isCollapsed: collapsed,
            allItems: sorted
        )
    }

    /// The turn pill, highest priority first: a failure, a collapsed request, a request whose
    /// panel is open, a subagent that needs input, then the turn's own label and Done.
    static func turnStatus(
        label: String?,
        fading: Bool,
        isAwaitingUser: Bool,
        items: [SessionChatActivityItem],
        keyboardVisible: Bool,
        awayFromEnd: Bool
    ) -> SessionChatTurnStatusSlot? {
        if let failure = items.first(where: { if case .requestFailed = $0 { true } else { false } }) {
            return SessionChatTurnStatusSlot(
                phase: .failed(label: SessionChatActivitySlotView.layout(failure).label),
                compact: false,
                leaving: false,
                tapItem: failure,
                isClosable: true,
                accessibilityLabel: SessionChatActivitySlotView.accessibilityLabel(failure)
            )
        }
        for request in [SessionChatActivityItem.reviewPermission, .answerQuestion] where items.contains(request) {
            return SessionChatTurnStatusSlot(
                phase: .attention(label: SessionChatActivitySlotView.layout(request).label, mark: .arrowUp),
                compact: false,
                leaving: false,
                tapItem: request,
                accessibilityLabel: SessionChatActivitySlotView.accessibilityLabel(request)
            )
        }
        if isAwaitingUser {
            let waiting = OpenCodeActivityText.waitingForYou
            return SessionChatTurnStatusSlot(
                phase: .attention(label: waiting, mark: .pulsingDot),
                compact: false,
                leaving: false,
                accessibilityLabel: waiting
            )
        }
        for case let .subagents(pill) in items where pill.needsInput {
            let asking = pill.askingLabel ?? pill.label
            return SessionChatTurnStatusSlot(
                phase: .attention(label: asking, mark: .pulsingDot),
                compact: false,
                leaving: false,
                tapItem: .subagents(pill),
                accessibilityLabel: SessionChatActivitySlotView.openRequestAccessibilityLabel(asking)
            )
        }
        guard let label else { return nil }
        return SessionChatTurnStatusSlot(
            phase: WorkingTurnIndicator.showsIdleMark(label: label) ? .done : .working(label: label),
            compact: awayFromEnd || keyboardVisible,
            leaving: fading
        )
    }

    /// What the bottom row folds to fit. A Working or Done pill folds first, and the secondary
    /// too if that is not enough. Attention and failed never fold, so the secondary gives way,
    /// and if the row still overflows the turn pill's label truncates.
    static func pillRowFold(widths: SessionChatPillRowWidths, turnCanFold: Bool) -> SessionChatPillRowFold {
        // Each left pill carries its spacing; the measured trailing width includes its own.
        func total(turn: CGFloat, secondary: CGFloat) -> CGFloat {
            let left = [turn, secondary, widths.overflow].filter { $0 > 0 }
            let gaps = CGFloat(widths.trailing > 0 ? left.count : max(left.count - 1, 0))
            return left.reduce(0, +) + gaps * widths.spacing + widths.trailing
        }
        guard widths.available > 0, total(turn: widths.turn, secondary: widths.secondary) > widths.available else {
            return SessionChatPillRowFold()
        }
        if turnCanFold {
            if total(turn: min(widths.turn, widths.compact), secondary: widths.secondary) <= widths.available {
                return SessionChatPillRowFold(turnCompact: true)
            }
            return SessionChatPillRowFold(turnCompact: true, secondaryCompact: widths.secondary > 0)
        }
        var fold = SessionChatPillRowFold(secondaryCompact: widths.secondary > 0)
        let excess = total(turn: widths.turn, secondary: min(widths.secondary, widths.compact)) - widths.available
        if excess > 0, widths.turn > 0 {
            fold.turnMaxWidth = max(widths.compact, widths.turn - excess)
        }
        return fold
    }

    /// The pill row's reserve while the reader is away from the end stays what it was, so a
    /// change in the row never moves the rows being read.
    static func reservesPillRow(wanted: Bool, held: Bool, awayFromEnd: Bool) -> Bool {
        awayFromEnd ? held : wanted
    }

    /// Away from the end with the keyboard down, the activity pills fold into one round pill.
    static func collapsesActivity(awayFromEnd: Bool, keyboardVisible: Bool) -> Bool {
        awayFromEnd && !keyboardVisible
    }

    /// Syncing sits beside the navigation pill. It shrinks to its round mark while that pill
    /// shows (the reader is away from the end) and with the keyboard up.
    static func syncingIsCompact(awayFromEnd: Bool, keyboardVisible: Bool) -> Bool {
        awayFromEnd || keyboardVisible
    }

    /// Top left: the goal first, then the branch, then todos, at most three.
    static func context(
        goal: SessionChatGoalPillUiState? = nil,
        todos: (completed: Int, total: Int)?,
        branch: SessionChatBranchPillUiState?
    ) -> [SessionChatContextItem] {
        var items: [SessionChatContextItem] = []
        if let goal {
            items.append(.goal(goal))
        }
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
