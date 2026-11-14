import Foundation
import OpenCodeKit

/// Today's goal: OpenCode's `/goal` on today's session (app plan, "Goal and Ask (M5)"). The app
/// sets it; after each turn a reviewer checks the work and sends the agent back for another round,
/// up to 50 per set or resume. Read with `get` on open and every reconnect, then followed through
/// `rpc.opencode.goal.changed`.
struct TodayGoal: Equatable, Sendable {
    enum Status: String, Equatable, Sendable {
        case active
        /// Paused by the user, by Stop, by the round limit, or by the reviewer (blocked).
        case paused
        /// The reviewer decided it's done. Kept until it's cleared or replaced.
        case complete
    }

    var id: String
    var objective: String
    var status: Status
    /// Why it last changed, as the server gives it; nil on a new goal.
    var reason: String?
    /// Continuations sent since it was set or last resumed: 0 after setting, 1 after resuming.
    var rounds: Int
    var maxRounds: Int
    /// Epoch ms of the server's last write.
    var updatedAt: Int64?

    init(id: String, objective: String, status: Status, reason: String? = nil, rounds: Int = 0, maxRounds: Int = 50, updatedAt: Int64? = nil) {
        self.id = id
        // A goal set with files carries their path lines; the goal line and sheet show it without.
        self.objective = AttachedFiles.strip(objective).text
        self.status = status
        self.reason = reason
        self.rounds = rounds
        self.maxRounds = maxRounds
        self.updatedAt = updatedAt
    }

    init(_ state: GoalStateV2) {
        self.init(
            id: state.id,
            objective: state.objective,
            status: Status(rawValue: state.status) ?? .active,
            reason: state.reason.flatMap { $0.isEmpty ? nil : $0 },
            rounds: state.rounds,
            maxRounds: state.maxRounds,
            updatedAt: state.updatedAt
        )
    }

    var isActive: Bool { status == .active }
}

/// What the goal line under the top bar says (app plan, "The goal" › Line states).
enum GoalLineState: Equatable, Sendable {
    /// No goal while idle: "Set today's goal", all `text3`.
    case empty
    /// Active and busy at round 0: "starting".
    case starting
    /// Active and busy: "round N", the server's `rounds`.
    case round(Int)
    /// Active while idle: the reviewer waits on a background shell.
    case waiting
    /// Paused, for any reason (the round limit included).
    case paused
    /// Complete; kept for the rest of the day.
    case done

    /// The line for `goal`; nil (no line) with no goal while the agent works.
    static func of(_ goal: TodayGoal?, busy: Bool) -> GoalLineState? {
        guard let goal else { return busy ? nil : .empty }
        switch goal.status {
        case .active:
            guard busy else { return .waiting }
            return goal.rounds == 0 ? .starting : .round(goal.rounds)
        case .paused:
            return .paused
        case .complete:
            return .done
        }
    }

    /// The quiet suffix after the objective (`mono`, `text3`); nil for the empty line.
    var suffix: String? {
        switch self {
        case .empty: nil
        case .starting: String(localized: "goal.line.starting", defaultValue: "starting")
        case let .round(n): String(localized: "goal.line.round", defaultValue: "round \(n)")
        case .waiting: String(localized: "goal.line.waiting", defaultValue: "waiting")
        case .paused: String(localized: "goal.line.paused", defaultValue: "paused")
        case .done: String(localized: "goal.line.done", defaultValue: "done")
        }
    }

    /// The `target` icon in `green` (active or done), else `text3`.
    var iconLit: Bool {
        switch self {
        case .starting, .round, .waiting, .done: true
        case .empty, .paused: false
        }
    }
}

/// The goal box's status, right of "/goal" (app plan, "The goal sheet" › The goal box).
enum GoalBoxStatus {
    static func text(_ goal: TodayGoal, busy: Bool) -> String {
        switch goal.status {
        case .active:
            guard busy else { return String(localized: "goal.box.active.waiting", defaultValue: "active · waiting") }
            if goal.rounds == 0 { return String(localized: "goal.box.active.starting", defaultValue: "active · starting") }
            return String(localized: "goal.box.active", defaultValue: "active · round \(goal.rounds) of \(goal.maxRounds)")
        case .paused:
            return String(localized: "goal.line.paused", defaultValue: "paused")
        case .complete:
            return String(localized: "goal.line.done", defaultValue: "done")
        }
    }

    /// The reason under the objective: for paused and done only.
    static func reason(_ goal: TodayGoal) -> String? {
        goal.status == .active ? nil : goal.reason
    }
}

/// The goal sheet's buttons, by the goal's state (app plan, "The goal sheet" › Buttons).
enum GoalSheetButtons: Equatable, Sendable {
    /// "Say the goal".
    case none
    /// Clear, Pause, Change.
    case active
    /// Clear, Resume, Change.
    case paused
    /// Clear, "New goal".
    case done

    static func of(_ goal: TodayGoal?) -> GoalSheetButtons {
        switch goal?.status {
        case nil: .none
        case .active?: .active
        case .paused?: .paused
        case .complete?: .done
        }
    }

    var offersPause: Bool { self == .active }
    var offersResume: Bool { self == .paused }
    var offersClear: Bool { self != .none }
}

/// What `/goal` is sent with: an objective, or one of its three words.
enum GoalCommand: Equatable, Sendable {
    case set(String)
    case pause
    case resume
    case clear

    /// The command's `text`.
    var text: String {
        switch self {
        case let .set(objective): objective
        case .pause: "pause"
        case .resume: "resume"
        case .clear: "clear"
        }
    }
}

/// The objective as the app sends it (app plan, "Set as goal from a draft" › Goal text and
/// Refused): the text trimmed, no prefix. The server reads `pause`, `resume` and `clear` in any case
/// as commands, so a dictated "Pause." is refused rather than sent; so is one over 4,000
/// characters, which the server refuses.
enum GoalText {
    static let maxLength = 4000
    static let commandWords: Set<String> = ["pause", "resume", "clear"]

    enum Refusal: Equatable, Sendable {
        /// "Say a longer goal · A single word is read as a goal command."
        case commandWord
        /// "That's too long for a goal · Goals are limited to 4,000 characters."
        case tooLong
    }

    static func objective(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Why `text` can't be sent as a goal; nil when it can. The server counts the length as
    /// JavaScript does (UTF-16 code units), so the phone does too.
    static func refusal(_ text: String) -> Refusal? {
        let objective = objective(text)
        if commandWords.contains(bare(objective)) { return .commandWord }
        if objective.utf16.count > maxLength { return .tooLong }
        return nil
    }

    /// Lower-cased, without trailing punctuation or spaces.
    private static func bare(_ text: String) -> String {
        var scalars = Substring(text.lowercased()).unicodeScalars
        let trailing = CharacterSet.punctuationCharacters.union(.symbols).union(.whitespacesAndNewlines)
        while let last = scalars.last, trailing.contains(last) { scalars.removeLast() }
        return String(scalars)
    }
}

/// What a goal command came to.
enum GoalOutcome: Equatable, Sendable {
    /// `204`: the state follows from the event.
    case done
    /// Refused on the phone; nothing was sent.
    case refused(GoalText.Refusal)
    /// `500`: the goal had already changed elsewhere; the app read it again.
    case stale
    /// Any other failure: "Couldn't change the goal".
    case failed
}

/// Reading and changing today's goal on the server.
protocol GoalAPI: Sendable {
    /// `POST /api/rpc/opencode.goal/get`; nil when the session has no goal.
    func goal(sessionID: String, directory: String) async throws -> TodayGoal?
    /// `POST /api/session/{id}/command` with `{"name": "goal", "text": …}` and the images as
    /// `files`. Throws `GoalAPIError.stale` for a `500` (the goal isn't in the state the command
    /// needs).
    func goalCommand(sessionID: String, text: String, images: [OutgoingImage]) async throws
}

enum GoalAPIError: Error, Equatable {
    /// `500 CommandExecutionError`: the goal changed elsewhere ("No active goal to pause.").
    case stale
}

/// Rounds of a goal, for the summary (app plan, "Goal rounds refresh the summary"): a goal runs as
/// one long run, so each round the reviewer starts counts as a turn end and refreshes the card
/// mid-run. Only a higher `rounds` than the last one seen counts; the first reading (on open or
/// reconnect) only sets where counting starts.
struct GoalRounds: Equatable, Sendable {
    private var last: (id: String, rounds: Int)?

    static func == (lhs: GoalRounds, rhs: GoalRounds) -> Bool {
        lhs.last?.id == rhs.last?.id && lhs.last?.rounds == rhs.last?.rounds
    }

    /// The goal read with `get`: where counting starts.
    mutating func read(_ goal: TodayGoal?) {
        last = goal.map { ($0.id, $0.rounds) }
    }

    /// The goal from an event: true when a new round started.
    mutating func observe(_ goal: TodayGoal?) -> Bool {
        defer { read(goal) }
        guard let goal, let last, last.id == goal.id else { return false }
        return goal.rounds > last.rounds
    }
}
