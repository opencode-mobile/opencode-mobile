import Foundation
import OpenCodeKit

/// What answering the agent and stopping it ask of the server (app plan, "Needs you and control").
protocol SessionControlAPI: Sendable {
    /// `POST …/permission/{id}/reply` on the request's own session. Throws `ControlError.gone` for
    /// a request the server no longer has.
    func replyToApproval(sessionID: String, requestID: String, decision: SessionStateAPIClient.PermissionDecision, note: String?) async throws
    /// `POST …/form/{id}/reply`. Throws `ControlError.gone` for a form already settled or gone.
    func answerQuestion(sessionID: String, formID: String, answer: [String: FormAnswerV2]) async throws
    /// `DELETE …/form/{id}?message=<note>`, so the run goes on.
    func dismissQuestion(sessionID: String, formID: String, note: String) async throws
    /// `POST /api/session/{id}/interrupt`: true when a run was stopped.
    func interrupt(sessionID: String) async throws -> Bool
    /// The subagents of `rootID` in `GET /api/session/active`.
    func runningSubagents(rootID: String) async throws -> [String]
    /// Running now: running subagents, and the shells in the project that today's session or a
    /// subagent started.
    func running(rootID: String, directory: String) async throws -> RunningSnapshot
    /// The tool calls of the session's latest assistant messages, newest first.
    func recentTools(sessionID: String) async throws -> [ToolPartV2]
}

enum ControlError: Error, Equatable {
    /// The server no longer has the request or form: it was answered elsewhere or the run ended.
    case gone
}

/// How answering a question or approval went.
enum NeedsReply: Equatable, Sendable {
    /// Sent: the sheet closes and the next one opens.
    case sent
    /// Answered elsewhere or the run ended: "No longer needed".
    case gone
    /// "Couldn't send": the sheet stays.
    case failed
}

/// The stop button's 3 s hold (app plan, "Stop" › Hold and Cancel).
enum StopHold {
    /// How long the button must be held.
    static let duration: TimeInterval = HexMotion.stopHold
    /// A hold shorter than this is a tap: "Hold to stop".
    static let tapLimit: TimeInterval = 0.35
    /// Sliding this far off the button cancels.
    static let slideLimit: CGFloat = 44

    /// What letting go (or sliding off) after `held` seconds does.
    enum Release: Equatable {
        /// Under 0.35 s: "Hold to stop · Keep pressing for 3 seconds. A tap does nothing."
        case tap
        /// Before 3 s: "Stop cancelled · The agent keeps running."
        case cancelled
        /// The hold was long enough; the stop has already gone.
        case stopped
    }

    static func release(after held: TimeInterval) -> Release {
        if held >= duration { return .stopped }
        return held < tapLimit ? .tap : .cancelled
    }

    /// The countdown's number after `elapsed` seconds: 3, 2, then 1.
    static func secondsLeft(after elapsed: TimeInterval) -> Int {
        HexStopWarning.secondsLeft(after: elapsed, of: duration)
    }
}

/// After Stop, a background subagent's note can wake today's session into a new run. Within 5 s
/// of the stop, a `<subagent …>` synthetic note followed by a run start is interrupted once more
/// (app plan, "Stop" › At 3 s, step 3).
struct StopRetry: Equatable, Sendable {
    static let window: TimeInterval = 5

    let rootID: String
    let deadline: Date
    private(set) var noteSeen = false
    private(set) var used = false

    init(rootID: String, stoppedAt: Date) {
        self.rootID = rootID
        deadline = stoppedAt.addingTimeInterval(Self.window)
    }

    /// True when this event means today's session should be interrupted once more now.
    mutating func observe(_ event: SessionEvent, at date: Date, busy: Bool) -> Bool {
        guard !used, date <= deadline else { return false }
        switch event {
        case let .inboxEnqueued(sessionID, _, _, text, _, kind, _):
            guard sessionID == rootID, kind == "synthetic", text.hasPrefix("<subagent") else { return false }
            noteSeen = true
            // Already running again: the note joined a run that started meanwhile.
            return fire(if: busy)
        case let .executionStarted(sessionID, _):
            guard sessionID == rootID, noteSeen else { return false }
            return fire(if: true)
        default:
            return false
        }
    }

    private mutating func fire(if condition: Bool) -> Bool {
        guard condition else { return false }
        used = true
        return true
    }
}
