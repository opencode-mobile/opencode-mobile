import Foundation

/// Copy for the Shells section and the shell output sheet. Pure, so both platforms
/// can pin the same strings in tests.
nonisolated enum SessionChatShellPresentation {
    static let sectionTitle = "Shells"
    static let emptyOutput = "No output yet"
    static let truncatedNotice = "Showing the latest output"
    static let copyLabel = "Copy"
    static let stopLabel = "Stop shell"
    static let stoppingLabel = "Stopping…"
    static let stopFailedMessage = "Couldn't stop the shell. Try again."

    /// Row timer: live while running, the run time once ended; nil when the end is unknown.
    static func elapsed(_ shell: AgentShell, nowMs: Int64) -> String? {
        let end = shell.status.isRunning ? nowMs : shell.finishedAtMs
        guard let end else { return nil }
        return SessionChatChildSessionElapsed.format(startEpochMs: shell.startedAtMs, nowEpochMs: end)
    }

    /// "Running · 1 min 12 s", "Exited with code 1 · 4 s", "Stopped · 2 min". An ended shell
    /// with no known end shows its state alone ("Stopped"), never a ticking time.
    static func statusLine(_ shell: AgentShell, nowMs: Int64) -> String {
        let status = statusLabel(shell.status)
        guard let start = ToolStartTime.normalizeEpochMs(shell.startedAtMs) else { return status }
        let end = shell.status.isRunning ? nowMs : ToolStartTime.normalizeEpochMs(shell.finishedAtMs)
        guard let end else { return status }
        return "\(status) · \(duration(ms: max(0, end - start)))"
    }

    static func statusLabel(_ status: AgentShellStatus) -> String {
        switch status {
        case .running: "Running"
        case .exited(let code?) where code != 0: "Exited with code \(code)"
        case .exited: "Exited"
        case .timedOut: "Timed out"
        case .stopped: "Stopped"
        case .failed: "Failed"
        }
    }

    /// "12 s", "1 min 12 s", "2 min", "1 h 5 min".
    static func duration(ms: Int64) -> String {
        let seconds = ms / 1_000
        if seconds < 60 { return "\(seconds) s" }
        let minutes = seconds / 60
        if minutes < 60 {
            let rest = seconds % 60
            return rest == 0 ? "\(minutes) min" : "\(minutes) min \(rest) s"
        }
        let hours = minutes / 60
        let restMinutes = minutes % 60
        return restMinutes == 0 ? "\(hours) h" : "\(hours) h \(restMinutes) min"
    }
}
