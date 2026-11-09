import Foundation

/// What a goal is doing now, as the pill, the sheet's tag and the status row show it.
enum SessionChatGoalActivity: Equatable {
    /// Active and the session works (or the reviewer checks): the pill spins.
    case working
    /// Active while the session waits on the user or on background work: no spinner.
    case waiting
    case paused
    case met
}

/// What an active goal waits on.
enum SessionChatGoalWait: Equatable {
    case permission
    case question
    /// A background shell of the session; the reviewer answered "wait" for it.
    case shell(command: String)
    case subagent
}

/// Session Chat's goal: pill, sheet and status row. Nil on the screen when the session has none.
struct SessionChatGoalUiState: Equatable {
    let goal: SessionGoal
    let activity: SessionChatGoalActivity
    var wait: SessionChatGoalWait?
    /// The action in flight; its button shows progress.
    var pending: SessionGoalAction?
    /// Reconnecting: the last known goal, dimmed, with its actions off.
    var isStale = false
    var sheetOpen = false
    /// Pause, Resume, Clear and New goal… can be used now.
    var actionsEnabled = true

    var pause: SessionGoalPause? { goal.pause }

    /// A paused or met goal gets its status row in the timeline.
    var hasStatusRow: Bool { goal.status != .active }

    /// The goal Session Chat shows, or nil: no goal, or a subagent session (a goal belongs to
    /// one session; children start without one).
    static func resolve(
        region: SessionChatGoalRegionState,
        isChildSession: Bool,
        isSessionBusy: Bool,
        hasPermission: Bool,
        hasQuestion: Bool,
        runningShellCommand: String?,
        hasRunningSubagent: Bool,
        canMutate: Bool
    ) -> SessionChatGoalUiState? {
        guard let goal = region.goal, !isChildSession else { return nil }
        let wait: SessionChatGoalWait? =
            if goal.status != .active {
                nil
            } else if hasPermission {
                .permission
            } else if hasQuestion {
                .question
            } else if isSessionBusy {
                nil
            } else if let runningShellCommand {
                .shell(command: runningShellCommand)
            } else if hasRunningSubagent {
                .subagent
            } else {
                nil
            }
        let activity: SessionChatGoalActivity =
            switch goal.status {
            case .paused: .paused
            case .complete: .met
            case .active: wait != nil ? .waiting : .working
            }
        return SessionChatGoalUiState(
            goal: goal,
            activity: activity,
            wait: wait,
            pending: region.pendingAction,
            isStale: region.isStale,
            sheetOpen: region.isSheetOpen,
            actionsEnabled: !region.isStale && region.pendingAction == nil && canMutate
        )
    }

    /// The top-left goal pill: spinner while working, goal mark while waiting, amber when paused,
    /// green check when met.
    var pill: SessionChatGoalPillUiState {
        SessionChatGoalPillUiState(
            activity: isStale && activity == .working ? .waiting : activity,
            label: SessionChatGoalCopy.pillLabel(activity),
            accessibilityLabel: SessionChatGoalCopy.pillAccessibilityLabel(self),
            isStale: isStale
        )
    }
}

/// What the goal pill draws.
struct SessionChatGoalPillUiState: Equatable {
    let activity: SessionChatGoalActivity
    let label: String
    let accessibilityLabel: String
    let isStale: Bool
}

/// Goal copy shared by the pill, sheet, timeline rows and composer.
enum SessionChatGoalCopy {
    /// Over this many characters the sheet clamps the objective to five lines with "Show all".
    static let objectiveClampCharacters = 260
    static let objectiveClampLines = 5

    static func pillLabel(_ activity: SessionChatGoalActivity) -> String {
        switch activity {
        case .working, .waiting: String(localized: "session_chat.goal.pill", defaultValue: "Goal")
        case .paused: String(localized: "session_chat.goal.pill_paused", defaultValue: "Goal paused")
        case .met: String(localized: "session_chat.goal.pill_met", defaultValue: "Goal met")
        }
    }

    static func pillAccessibilityLabel(_ state: SessionChatGoalUiState) -> String {
        let goal = state.goal
        switch state.activity {
        case .working, .waiting:
            let rounds = goal.rounds
            let maxRounds = goal.maxRounds
            return String(
                localized: "session_chat.goal.pill_accessibility_active",
                defaultValue: "Goal active, round \(rounds) of \(maxRounds)"
            )
        case .paused:
            let title = pauseTitle(goal)
            return String(localized: "session_chat.goal.pill_accessibility_paused", defaultValue: "Goal paused. \(title)")
        case .met:
            return pillLabel(.met)
        }
    }

    static var sheetTitle: String { String(localized: "session_chat.goal.sheet_title", defaultValue: "Goal") }

    static func tag(_ activity: SessionChatGoalActivity) -> String {
        switch activity {
        case .working: String(localized: "session_chat.goal.tag_working", defaultValue: "Working")
        case .waiting: String(localized: "session_chat.goal.tag_waiting", defaultValue: "Waiting")
        case .paused: String(localized: "session_chat.goal.tag_paused", defaultValue: "Paused")
        case .met: String(localized: "session_chat.goal.tag_met", defaultValue: "Met")
        }
    }

    static var showAll: String { String(localized: "session_chat.goal.show_all", defaultValue: "Show all") }
    static var showLess: String { String(localized: "session_chat.goal.show_less", defaultValue: "Show less") }

    /// "Goal paused" for the reviewer's own words; a fixed title for a known server reason.
    static func pauseTitle(_ goal: SessionGoal) -> String {
        let pause = goal.pause ?? SessionGoalPause.classify(goal.reason)
        switch pause.kind {
        case .byUser:
            return String(localized: "session_chat.goal.paused_by_user", defaultValue: "You paused the goal")
        case .stopped:
            return String(
                localized: "session_chat.goal.paused_stopped",
                defaultValue: "You stopped the turn, so the goal paused"
            )
        case .failed:
            return String(localized: "session_chat.goal.paused_failed", defaultValue: "Goal paused after an error")
        case .noProgress:
            let stalls = SessionGoalLimits.maxStalls
            return String(
                localized: "session_chat.goal.paused_no_progress",
                defaultValue: "Goal paused: no progress for \(stalls) rounds"
            )
        case .roundLimit:
            let maxRounds = goal.maxRounds
            return String(localized: "session_chat.goal.paused_round_limit", defaultValue: "Goal paused after \(maxRounds) rounds")
        case .serverRestart:
            return String(localized: "session_chat.goal.paused_restart", defaultValue: "Goal paused: the server restarted")
        case .reviewerFailed:
            return String(
                localized: "session_chat.goal.paused_reviewer",
                defaultValue: "Goal paused: the reviewer could not decide"
            )
        case .backgroundQuiet:
            return String(localized: "session_chat.goal.paused_quiet", defaultValue: "Goal paused: background work went quiet")
        case .other:
            return String(localized: "session_chat.goal.paused", defaultValue: "Goal paused")
        }
    }

    /// The reason line under a pause title; nil when the title says it all.
    static func pauseReason(_ goal: SessionGoal) -> String? {
        guard let pause = goal.pause, !pause.hidesReason else { return nil }
        guard let reason = goal.reason, !reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return reason
    }

    static var metTitle: String { String(localized: "session_chat.goal.met", defaultValue: "Goal met") }

    /// "Round 3 of 50", "First turn · round 0 of 50", "Paused in round 3 of 50", "Met in round 4".
    static func progress(_ goal: SessionGoal) -> String {
        let rounds = goal.rounds
        let maxRounds = goal.maxRounds
        switch goal.status {
        case .active:
            if rounds == 0 {
                return String(localized: "session_chat.goal.first_turn", defaultValue: "First turn · round 0 of \(maxRounds)")
            }
            return String(localized: "session_chat.goal.round", defaultValue: "Round \(rounds) of \(maxRounds)")
        case .paused:
            return String(
                localized: "session_chat.goal.paused_in_round",
                defaultValue: "Paused in round \(rounds) of \(maxRounds)"
            )
        case .complete:
            return String(localized: "session_chat.goal.met_in_round", defaultValue: "Met in round \(rounds)")
        }
    }

    /// The meter's fill, 0...1, with a sliver at round 0 so it reads as a meter.
    static func progressFraction(_ goal: SessionGoal) -> Double {
        min(max(Double(goal.rounds) / Double(max(goal.maxRounds, 1)), 0.01), 1)
    }

    /// "No progress for 2 rounds; pauses after 3", from one stalled round on; nil otherwise.
    static func stalls(_ goal: SessionGoal) -> String? {
        guard goal.status == .active, let stalls = goal.stalls, stalls >= 1 else { return nil }
        // The limit goes in as text so the plural follows the stall count alone.
        let limit = String(SessionGoalLimits.maxStalls)
        return String(
            localized: "session_chat.goal.stalls",
            defaultValue: "No progress for \(stalls) rounds; pauses after \(limit)"
        )
    }

    /// The sheet's reviewer box while active: its caption and text.
    static func reviewerNote(_ state: SessionChatGoalUiState) -> (caption: String, text: String, needsUser: Bool) {
        let limit = SessionGoalLimits.waitLimitMinutes
        switch state.wait {
        case let .shell(command):
            return (
                String(localized: "session_chat.goal.waiting", defaultValue: "Waiting"),
                String(
                    localized: "session_chat.goal.waiting_shell",
                    defaultValue: "For a background shell to finish: \(command). Pauses if nothing runs for \(limit) minutes."
                ),
                false
            )
        case .subagent:
            return (
                String(localized: "session_chat.goal.waiting", defaultValue: "Waiting"),
                String(
                    localized: "session_chat.goal.waiting_subagent",
                    defaultValue: "For a subagent to finish. Pauses if nothing runs for \(limit) minutes."
                ),
                false
            )
        case .permission:
            return (
                String(localized: "session_chat.goal.waiting_on_you", defaultValue: "Waiting on you"),
                String(
                    localized: "session_chat.goal.waiting_permission",
                    defaultValue: "A permission request is open. The goal carries on once you answer it."
                ),
                true
            )
        case .question:
            return (
                String(localized: "session_chat.goal.waiting_on_you", defaultValue: "Waiting on you"),
                String(
                    localized: "session_chat.goal.waiting_question",
                    defaultValue: "A question is open. The goal carries on once you answer it."
                ),
                true
            )
        case nil:
            if let reason = state.goal.reason, !reason.isEmpty {
                return (
                    String(localized: "session_chat.goal.reviewer_last_round", defaultValue: "Reviewer, last round"),
                    String(localized: "session_chat.goal.not_met_yet", defaultValue: "Not met yet: \(reason)"),
                    false
                )
            }
            return (
                String(localized: "session_chat.goal.reviewer", defaultValue: "Reviewer"),
                String(localized: "session_chat.goal.reviewer_pending", defaultValue: "Checks the work when this turn ends."),
                false
            )
        }
    }

    static var evidence: String { String(localized: "session_chat.goal.evidence", defaultValue: "Evidence") }

    static var reconnecting: String {
        String(localized: "session_chat.goal.reconnecting", defaultValue: "Reconnecting. Showing the last known goal.")
    }

    static func footnote(_ goal: SessionGoal) -> String {
        let maxRounds = goal.maxRounds
        switch goal.status {
        case .active:
            return String(
                localized: "session_chat.goal.footnote_active",
                defaultValue: "After each turn a reviewer checks the work against the goal. The agent keeps going until it is met, it needs you, or \(maxRounds) rounds pass."
            )
        case .paused:
            return String(
                localized: "session_chat.goal.footnote_paused",
                defaultValue: "Resume starts a fresh budget of \(maxRounds) rounds."
            )
        case .complete:
            return String(localized: "session_chat.goal.footnote_met", defaultValue: "A new goal replaces this one.")
        }
    }

    static func actionLabel(_ action: SessionGoalAction, pending: Bool) -> String {
        switch (action, pending) {
        case (.pause, false): String(localized: "session_chat.goal.pause", defaultValue: "Pause")
        case (.pause, true): String(localized: "session_chat.goal.pausing", defaultValue: "Pausing…")
        case (.resume, false): String(localized: "session_chat.goal.resume", defaultValue: "Resume")
        case (.resume, true): String(localized: "session_chat.goal.resuming", defaultValue: "Resuming…")
        case (.clear, false): String(localized: "session_chat.goal.clear", defaultValue: "Clear")
        case (.clear, true): String(localized: "session_chat.goal.clearing", defaultValue: "Clearing…")
        }
    }

    static var newGoal: String { String(localized: "session_chat.goal.new", defaultValue: "New goal…") }

    static func actionFailed(_ action: SessionGoalAction, message: String?) -> String {
        switch (action, message) {
        case let (.pause, message?):
            String(localized: "session_chat.goal.pause_failed_reason", defaultValue: "Couldn’t pause the goal: \(message)")
        case (.pause, nil):
            String(localized: "session_chat.goal.pause_failed", defaultValue: "Couldn’t pause the goal")
        case let (.resume, message?):
            String(localized: "session_chat.goal.resume_failed_reason", defaultValue: "Couldn’t resume the goal: \(message)")
        case (.resume, nil):
            String(localized: "session_chat.goal.resume_failed", defaultValue: "Couldn’t resume the goal")
        case let (.clear, message?):
            String(localized: "session_chat.goal.clear_failed_reason", defaultValue: "Couldn’t clear the goal: \(message)")
        case (.clear, nil):
            String(localized: "session_chat.goal.clear_failed", defaultValue: "Couldn’t clear the goal")
        }
    }

    // MARK: Timeline

    static var goalSet: String { String(localized: "session_chat.goal.set", defaultValue: "Goal set") }
    static var ended: String { String(localized: "session_chat.goal.ended", defaultValue: "ended") }
    static var bubbleLabel: String { String(localized: "session_chat.goal.bubble_label", defaultValue: "Goal") }

    /// The Goal set row's text: the kickoff description without its "Goal: " lead.
    static func goalSetValue(_ description: String) -> String {
        TimelineNoticeCopy.goalSetValue(description)
    }

    // MARK: Composer

    static var objectiveHint: String {
        String(localized: "session_chat.goal.objective_hint", defaultValue: "Describe what “done” looks like…")
    }

    static var resumePlaceholder: String {
        String(localized: "session_chat.goal.resume_placeholder", defaultValue: "Reply, then resume the goal…")
    }

    /// "3,812 / 4,000 characters", or with "· shorten the objective to send" past the limit.
    static func count(_ length: Int) -> String {
        let count = length.formatted()
        let limit = SessionGoalLimits.maxObjectiveLength.formatted()
        if length > SessionGoalLimits.maxObjectiveLength {
            return String(
                localized: "session_chat.goal.count_over",
                defaultValue: "\(count) / \(limit) characters · shorten the objective to send"
            )
        }
        return String(localized: "session_chat.goal.count", defaultValue: "\(count) / \(limit) characters")
    }

    /// The `/goal` command row's subtitle while a goal exists; nil keeps the server's description.
    static func commandSubtitle(_ goal: SessionGoal?) -> String? {
        switch goal?.status {
        case nil: nil
        case .active?: String(localized: "session_chat.goal.command_replaces", defaultValue: "Replaces your current goal")
        case .paused?:
            String(localized: "session_chat.goal.command_replaces_paused", defaultValue: "Replaces your current goal (paused)")
        case .complete?:
            String(localized: "session_chat.goal.command_replaces_met", defaultValue: "Replaces your current goal (met)")
        }
    }

    /// The composer asks for a reply while the reviewer paused the goal in its own words.
    static func resumesAfterReply(_ state: SessionChatGoalUiState?) -> Bool {
        state?.pause?.isKnown == false
    }
}

/// What the composer adds for a `/goal …` draft: a hint, a count, and whether Send may run.
struct SessionChatGoalDraftUiState: Equatable {
    /// "Describe what “done” looks like…" or the character count; nil for neither.
    let footnote: String?
    /// Past the server's limit: the footnote reads as an error.
    let isOverLimit: Bool
    let canSend: Bool

    /// Nil when the draft is not a `/goal <objective>` command.
    static func resolve(text: String, goalCommandAvailable: Bool) -> SessionChatGoalDraftUiState? {
        guard goalCommandAvailable, let objective = SessionGoalDraft.objective(text) else { return nil }
        let over = objective.count > SessionGoalLimits.maxObjectiveLength
        let footnote: String? =
            if objective.isEmpty {
                SessionGoalDraft.awaitsObjective(text) ? SessionChatGoalCopy.objectiveHint : nil
            } else if SessionGoalDraft.showsCount(objective: objective) {
                SessionChatGoalCopy.count(objective.count)
            } else {
                nil
            }
        return SessionChatGoalDraftUiState(
            footnote: footnote,
            isOverLimit: over,
            canSend: SessionGoalDraft.canSend(objective: objective)
        )
    }
}

/// The goal's status row in the timeline: built from the goal state, not a message.
enum SessionChatGoalTimeline {
    private static let statusRowPrefix = "goal-status:"

    static func statusRowID(goalID: String) -> String { statusRowPrefix + goalID }

    static func isStatusRow(_ row: SessionChatRow) -> Bool { isStatusRowID(row.id) }

    static func isStatusRowID(_ id: String) -> Bool { id.hasPrefix(statusRowPrefix) }

    /// `rows` with a paused or met goal's status row placed by its `updatedAt`: after every row
    /// sent by then. Rows without a send time count as earlier.
    static func withStatusRow(_ rows: [SessionChatRow], goal: SessionChatGoalUiState?) -> [SessionChatRow] {
        guard let goal, goal.hasStatusRow else { return rows }
        let updatedAt = goal.goal.updatedAt
        var index = rows.count
        while index > 0 {
            guard let sentAt = rows[index - 1].sentAtEpochMs, sentAt > updatedAt else { break }
            index -= 1
        }
        let row = SessionChatRow(
            id: statusRowID(goalID: goal.goal.id),
            role: .notice,
            text: "",
            metadataLabel: "",
            sentAtEpochMs: updatedAt
        )
        var result = rows
        result.insert(row, at: index)
        return result
    }

    /// The Goal set rows and status row whose look depends on the goal, keyed by what they
    /// show, so the timeline host reconfigures them when it changes.
    static func renderKeys(rows: [SessionChatRow], goal: SessionChatGoalUiState?) -> [String: String] {
        var keys: [String: String] = [:]
        for row in rows {
            if isStatusRow(row) {
                if let goal { keys[row.id] = statusRowKey(goal) } else { keys[row.id] = "" }
                continue
            }
            guard row.role == .notice else { continue }
            for block in row.blocks {
                guard case let .notice(_, notice) = block, case let .goalSet(goalID, _) = notice.kind else { continue }
                let current = goal?.goal.id == goalID
                keys[row.id] = current ? "current:\(goal?.isStale == true)" : "ended"
            }
        }
        return keys
    }

    private static func statusRowKey(_ goal: SessionChatGoalUiState) -> String {
        [
            goal.goal.status.rawValue,
            goal.goal.reason ?? "",
            goal.pending?.rawValue ?? "",
            goal.actionsEnabled ? "1" : "0",
        ].joined(separator: "|")
    }
}
