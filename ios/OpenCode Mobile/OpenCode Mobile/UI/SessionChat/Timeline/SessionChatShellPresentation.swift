import Foundation

/// Copy for the Shells section and the shell output sheet, from the String Catalog.
/// Pure, so both platforms can pin the same English in tests.
nonisolated enum SessionChatShellPresentation {
    static var stopFailedMessage: String {
        String(localized: "session_chat.shell.stop_failed", defaultValue: "Couldn't stop the shell. Try again.")
    }

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
        let elapsed = duration(ms: max(0, end - start))
        return String(localized: "timeline.list_separator", defaultValue: "\(status) · \(elapsed)")
    }

    static func statusLabel(_ status: AgentShellStatus) -> String {
        switch status {
        case .running: String(localized: "session_chat.shell.status.running", defaultValue: "Running")
        case .exited(let code?) where code != 0: String(localized: "tool.error.exit_code", defaultValue: "Exited with code \(code)")
        case .exited: String(localized: "session_chat.shell.status.exited", defaultValue: "Exited")
        case .timedOut: String(localized: "session_chat.shell.status.timed_out", defaultValue: "Timed out")
        case .stopped: String(localized: "tool.status.stopped", defaultValue: "Stopped")
        case .failed: String(localized: "tool.error.failed", defaultValue: "Failed")
        }
    }

    /// "12 s", "1 min 12 s", "2 min", "1 h 5 min".
    static func duration(ms: Int64) -> String {
        let seconds = Int(ms / 1_000)
        if seconds < 60 {
            return String(localized: "session_chat.shell.duration_seconds", defaultValue: "\(seconds) s")
        }
        let minutes = seconds / 60
        if minutes < 60 {
            let rest = seconds % 60
            return rest == 0
                ? String(localized: "session_chat.shell.duration_minutes", defaultValue: "\(minutes) min")
                : String(localized: "session_chat.shell.duration_minutes_seconds", defaultValue: "\(minutes) min \(rest) s")
        }
        let hours = minutes / 60
        let restMinutes = minutes % 60
        return restMinutes == 0
            ? String(localized: "session_chat.shell.duration_hours", defaultValue: "\(hours) h")
            : String(localized: "session_chat.shell.duration_hours_minutes", defaultValue: "\(hours) h \(restMinutes) min")
    }
}
