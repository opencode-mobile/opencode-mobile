import Foundation

/// Where a session's `/goal` stands.
nonisolated enum SessionGoalStatus: String, Equatable, Sendable {
    /// The reviewer loop runs after each turn.
    case active
    /// Waits for the user: paused by them, by Stop, by a failure, or by the reviewer.
    case paused
    /// The reviewer found the objective met.
    case complete
}

/// A session's goal: an objective the agent keeps working on until a reviewer finds it met.
/// Only v2 servers with the goal plugin have one.
nonisolated struct SessionGoal: Equatable, Sendable {
    let id: String
    let objective: String
    let status: SessionGoalStatus
    /// Why the goal last changed: the reviewer's reason, or what paused it.
    let reason: String?
    /// Continuations sent since the goal was set or last resumed.
    let rounds: Int
    let maxRounds: Int
    /// Rounds in a row without progress.
    let stalls: Int?
    /// Epoch milliseconds of the last change.
    let updatedAt: Int64

    init(
        id: String,
        objective: String,
        status: SessionGoalStatus,
        reason: String? = nil,
        rounds: Int,
        maxRounds: Int,
        stalls: Int? = nil,
        updatedAt: Int64
    ) {
        self.id = id
        self.objective = objective
        self.status = status
        self.reason = reason
        self.rounds = rounds
        self.maxRounds = maxRounds
        self.stalls = stalls
        self.updatedAt = updatedAt
    }

    /// How a paused goal reads; nil unless paused.
    var pause: SessionGoalPause? {
        status == .paused ? SessionGoalPause.classify(reason) : nil
    }
}

/// What paused a goal, read from the server's reason. Anything unknown is the reviewer's
/// own words (`other`), shown under a plain "Goal paused".
nonisolated enum SessionGoalPauseKind: String, CaseIterable, Equatable, Sendable {
    case byUser
    case stopped
    case failed
    case noProgress
    case roundLimit
    case serverRestart
    case reviewerFailed
    case backgroundQuiet
    case other
}

/// A paused goal's kind, and whether its reason line is hidden because the title already says
/// it all ("You paused the goal").
nonisolated struct SessionGoalPause: Equatable, Sendable {
    let kind: SessionGoalPauseKind
    let hidesReason: Bool

    /// A known server reason; anything else is the reviewer's own words.
    var isKnown: Bool { kind != .other }

    /// Reason strings the goal plugin writes, in match order: pattern, kind, hides the reason.
    private static let rules: [(String, SessionGoalPauseKind, Bool)] = [
        (#"^Paused by the user$"#, .byUser, true),
        (#"^Stopped by the user$"#, .stopped, true),
        (#"^The session failed: "#, .failed, false),
        (#"^Not making progress: "#, .noProgress, false),
        (#"^Reached the limit of \d+ rounds$"#, .roundLimit, true),
        (#"^The server restarted while the goal was active$"#, .serverRestart, true),
        (#"^The goal reviewer"#, .reviewerFailed, false),
        (#"^Nothing ran for \d+ minutes"#, .backgroundQuiet, false),
    ]

    static func classify(_ reason: String?) -> SessionGoalPause {
        let text = reason ?? ""
        for (pattern, kind, hides) in rules where text.range(of: pattern, options: .regularExpression) != nil {
            return SessionGoalPause(kind: kind, hidesReason: hides)
        }
        return SessionGoalPause(kind: .other, hidesReason: text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
}

/// Pause, resume or clear: the text of the `goal` session command.
nonisolated enum SessionGoalAction: String, CaseIterable, Equatable, Sendable {
    case pause
    case resume
    case clear

    var commandText: String { rawValue }
}

nonisolated enum SessionGoalLimits {
    /// The server rejects longer objectives.
    static let maxObjectiveLength = 4_000
    /// The composer counts characters from here on.
    static let objectiveCountThreshold = 3_600
    /// The server pauses after this many rounds without progress.
    static let maxStalls = 3
    /// An active goal waiting on background work pauses after this many quiet minutes.
    static let waitLimitMinutes = 30
}

/// The goal kickoff: what started a goal in the transcript.
nonisolated enum SessionGoalKickoff {
    static let commandName = "goal"
    static let metadataSource = "goal"
    static let descriptionPrefix = "Goal: "
    /// The first line of the server's kickoff instruction.
    static let instructionLead = "A goal is now active for this session."

    /// The goal id when `metadata` marks a goal message (`{source: "goal", goalID}`).
    static func goalID(source: String?, goalID: String?) -> String? {
        guard source == metadataSource, let goalID, !goalID.isEmpty else { return nil }
        return goalID
    }

    /// The objective inside a kickoff instruction (`<untrusted_objective>` block), unescaped.
    /// Nil for any other text, so a prompt the user typed never reads as a kickoff.
    static func objective(fromKickoffText text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix(instructionLead),
              let open = trimmed.range(of: "<untrusted_objective>"),
              let close = trimmed.range(of: "</untrusted_objective>", range: open.upperBound ..< trimmed.endIndex)
        else { return nil }
        let objective = String(trimmed[open.upperBound ..< close.lowerBound])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !objective.isEmpty else { return nil }
        return unescape(objective)
    }

    /// The objective a kickoff bubble shows: the current goal's own when it is this kickoff's
    /// goal, else the one parsed from the instruction text.
    static func displayObjective(kickoffText: String, goalID: String?, current: SessionGoal?) -> String? {
        if let current, let goalID, current.id == goalID { return current.objective }
        return objective(fromKickoffText: kickoffText)
    }

    /// Undoes the server's `&`, `<`, `>` escaping, `&amp;` last.
    static func unescape(_ text: String) -> String {
        text.replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&amp;", with: "&")
    }
}

/// A goal change for one session, from the live event stream.
nonisolated struct SessionGoalChange: Equatable, Sendable {
    let sessionID: String
    /// Nil: the goal was cleared.
    let goal: SessionGoal?
}

/// A `/goal <objective>` draft in the composer.
nonisolated enum SessionGoalDraft {
    private static let prefix = "/" + SessionGoalKickoff.commandName

    /// The objective of a `/goal …` draft; nil when the draft is not a goal command or is a
    /// control (pause, resume, clear). Quotes stay as typed; the server strips a matching pair.
    static func objective(_ text: String) -> String? {
        let start = text.drop { $0.isWhitespace }
        guard start.hasPrefix(prefix) else { return nil }
        let rest = start.dropFirst(prefix.count)
        if let first = rest.first, !first.isWhitespace { return nil }
        let objective = rest.trimmingCharacters(in: .whitespacesAndNewlines)
        if SessionGoalAction.allCases.contains(where: { $0.commandText.caseInsensitiveCompare(objective) == .orderedSame }) {
            return nil
        }
        return objective
    }

    /// Only the command so far ("/goal "), waiting for an objective.
    static func awaitsObjective(_ text: String) -> Bool {
        objective(text)?.isEmpty == true && text.drop { $0.isWhitespace }.count > prefix.count
    }

    /// Send stays off while the objective is empty or over the server's limit.
    static func canSend(objective: String) -> Bool {
        !objective.isEmpty && objective.count <= SessionGoalLimits.maxObjectiveLength
    }

    static func showsCount(objective: String) -> Bool {
        objective.count >= SessionGoalLimits.objectiveCountThreshold
    }
}
