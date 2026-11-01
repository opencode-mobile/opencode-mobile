import Foundation

/// Where an agent-run shell command is.
nonisolated enum AgentShellStatus: Equatable, Sendable {
    case running
    /// Finished on its own; `code` is the exit code when the server reports one.
    case exited(code: Int?)
    case timedOut
    /// Stopped by the user (Stop shell, or an interrupted run).
    case stopped
    case failed

    var isRunning: Bool { self == .running }
}

/// A shell command the agent (or a user `!` command) runs for the open session.
nonisolated struct AgentShell: Equatable, Identifiable, Sendable {
    /// v2: the `sh_…` shell id; v1: the bash tool part id.
    let id: String
    let sessionID: String
    let command: String
    /// What the agent said the command does (tool input `description`), when known.
    let description: String?
    var status: AgentShellStatus
    let startedAtMs: Int64?
    var finishedAtMs: Int64?
    /// The server can stop this shell on its own (v2 while running); v1 never can.
    var canStop: Bool

    init(
        id: String,
        sessionID: String,
        command: String,
        description: String? = nil,
        status: AgentShellStatus,
        startedAtMs: Int64? = nil,
        finishedAtMs: Int64? = nil,
        canStop: Bool = false
    ) {
        self.id = id
        self.sessionID = sessionID
        self.command = command
        self.description = description
        self.status = status
        self.startedAtMs = startedAtMs
        self.finishedAtMs = finishedAtMs
        self.canStop = canStop && status.isRunning
    }

    /// This shell after it ended with `status`; an ended shell cannot be stopped.
    func ended(_ status: AgentShellStatus, atMs: Int64?) -> AgentShell {
        var next = self
        next.status = status
        next.finishedAtMs = finishedAtMs ?? atMs
        next.canStop = false
        return next
    }
}

/// The latest output of one shell, combined stdout and stderr.
nonisolated struct AgentShellOutput: Equatable, Sendable {
    /// The app keeps at most this many characters, the newest ones.
    static let maxCharacters = 30_000

    let text: String
    /// Older output was dropped, by the app's cap or by the server.
    let isTruncated: Bool

    static let empty = AgentShellOutput(text: "", isTruncated: false)

    init(text: String, isTruncated: Bool) {
        self.text = text
        self.isTruncated = isTruncated
    }

    /// `text` cut to its last `maxCharacters` characters; cutting marks it truncated.
    static func capped(_ text: String, truncated: Bool) -> AgentShellOutput {
        guard text.count > maxCharacters else {
            return AgentShellOutput(text: text, isTruncated: truncated)
        }
        return AgentShellOutput(text: String(text.suffix(maxCharacters)), isTruncated: true)
    }
}

/// One server-side change to the shells of a session (v2 `shell.*` events).
nonisolated enum AgentShellChange: Equatable, Sendable {
    case created(AgentShell)
    case ended(id: String, status: AgentShellStatus, atMs: Int64?)
    case removed(id: String)
}

nonisolated enum AgentShellError: Error, Equatable {
    /// The server cannot stop shells one by one.
    case stopUnsupported
}
