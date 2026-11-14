import Foundation
import OpenCodeKit

/// The Details sheet's sections, in their order (app plan, "Details" › Order). A section with
/// nothing in it is left out; with none, the sheet reads "Nothing running right now."
enum DetailsSection: Equatable, Sendable, CaseIterable {
    case needs
    case waiting
    /// Asked on the side: the phone's closed answers (M5).
    case asked
    case running
    case todo
    case recent

    struct Counts: Equatable, Sendable {
        var needs = 0
        var waiting = 0
        var asked = 0
        var running = 0
        var todo = 0
        var recent = 0
    }

    static func shown(_ counts: Counts) -> [DetailsSection] {
        allCases.filter { section in
            switch section {
            case .needs: counts.needs > 0
            case .waiting: counts.waiting > 0
            case .asked: counts.asked > 0
            case .running: counts.running > 0
            case .todo: counts.todo > 0
            case .recent: counts.recent > 0
            }
        }
    }
}

/// One row of Running now: a subagent (green dot, its title, "Subagent · <agent>") or an agent
/// shell running longer than 10 s (neutral dot, the command, "Shell command"), with the time it has
/// run.
struct RunningRow: Equatable, Sendable, Identifiable {
    enum Kind: Equatable, Sendable {
        case subagent(agent: String)
        case shell
    }

    var id: String
    var kind: Kind
    var title: String
    var since: Date
}

enum RunningNow {
    /// At most this many rows, then "+N more".
    static let shownRows = 3

    /// Subagents first, then shells that have run for 10 s, each oldest first. `total` is what the
    /// Details link counts ("N running ·").
    static func rows(_ state: LiveSessionState, at date: Date) -> (rows: [RunningRow], more: Int, total: Int) {
        let subagents = state.runningChildren.map { id in
            let child = state.childSessions[id]
            let agent = child?.agent ?? ""
            let title = [child?.title, child?.agent].compactMap { $0 }.first { !$0.isEmpty } ?? id
            return RunningRow(id: id, kind: .subagent(agent: agent), title: title, since: state.childBusySince[id] ?? date)
        }
        let shells = state.shells.compactMap { id, started -> RunningRow? in
            guard date.timeIntervalSince(started) >= LiveSessionState.shellDelay else { return nil }
            return RunningRow(id: id, kind: .shell, title: state.shellEntries[id]?.command ?? "", since: started)
        }
        let all = subagents.sorted(by: oldestFirst) + shells.sorted(by: oldestFirst)
        return (Array(all.prefix(shownRows)), max(0, all.count - shownRows), all.count)
    }

    private static func oldestFirst(_ a: RunningRow, _ b: RunningRow) -> Bool {
        a.since == b.since ? a.id < b.id : a.since < b.since
    }
}

/// One of the latest steps in Recent: an edit, a command or a subagent start.
struct RecentStep: Equatable, Sendable, Identifiable {
    enum Kind: Equatable, Sendable {
        case edit
        case command
        case subagent
    }

    var id: String
    var kind: Kind
    /// The file path, the command, or the subagent's description.
    var title: String
    /// "Edited · +2 −0", "Done", "Subagent · general", …; nil for none.
    var detail: String?
    /// When the step finished (else when it started).
    var at: Date?

    /// Paths and commands show in `code`.
    var titleIsCode: Bool { kind != .subagent }
}

/// Details' Recent (app plan, "Details" › Recent): the latest edits, commands and subagent starts
/// of today's session, newest first. Reads, searches, questions, to-do writes and other tools are
/// left out, and so are calls still running.
enum RecentSteps {
    /// Assistant messages read for it.
    static let messageLimit = 20
    /// At most this many rows.
    static let shownRows = 3
    /// After a tool finishes, Recent is read again this much later, while Details is open.
    static let rereadDelay: Duration = .seconds(1)

    static let editTools: Set<String> = ["edit", "write", "patch"]
    static let commandTools: Set<String> = ["shell", "bash"]
    static let subagentTools: Set<String> = ["subagent", "task"]

    /// `parts` newest first.
    static func make(_ parts: [ToolPartV2]) -> [RecentStep] {
        Array(parts.lazy.compactMap(step).prefix(shownRows))
    }

    static func step(_ part: ToolPartV2) -> RecentStep? {
        guard part.status == "completed" || part.status == "error" else { return nil }
        let id = part.id ?? UUID().uuidString
        let at = (part.completedMs ?? part.createdMs).map { Date(timeIntervalSince1970: Double($0) / 1000) }
        if editTools.contains(part.name) {
            // An edit that didn't happen (refused or failed) changed nothing.
            guard part.status == "completed" else { return nil }
            return editStep(part, id: id, at: at)
        }
        if commandTools.contains(part.name) {
            guard let command = part.command, !command.isEmpty else { return nil }
            return RecentStep(id: id, kind: .command, title: command, detail: commandOutcome(part), at: at)
        }
        if subagentTools.contains(part.name) {
            let agent = part.agent ?? ""
            let title = [part.description, part.agent].compactMap { $0 }.first { !$0.isEmpty }
            guard let title else { return nil }
            return RecentStep(
                id: id,
                kind: .subagent,
                title: title,
                detail: String(localized: "details.running.subagent", defaultValue: "Subagent · \(agent)"),
                at: at
            )
        }
        return nil
    }

    private static func editStep(_ part: ToolPartV2, id: String, at: Date?) -> RecentStep? {
        let files = part.files
        guard let first = files.first else {
            guard let path = part.path, !path.isEmpty else { return nil }
            return RecentStep(id: id, kind: .edit, title: path, detail: nil, at: at)
        }
        let added = files.reduce(0) { $0 + ($1.additions ?? 0) }
        let removed = files.reduce(0) { $0 + ($1.deletions ?? 0) }
        let detail: String = if files.count > 1 {
            String(localized: "details.recent.files", defaultValue: "\(files.count) files · +\(added) −\(removed)")
        } else if first.status == "added" {
            String(localized: "details.recent.created", defaultValue: "Created · +\(added)")
        } else {
            String(localized: "details.recent.edited", defaultValue: "Edited · +\(added) −\(removed)")
        }
        return RecentStep(id: id, kind: .edit, title: first.file, detail: detail, at: at)
    }

    /// "Done", "Failed · exit 1", "Stopped" or "Denied".
    static func commandOutcome(_ part: ToolPartV2) -> String? {
        if part.status == "error" {
            let message = (part.errorMessage ?? "").lowercased()
            if part.errorType == "permission.rejected" || (part.errorType == "aborted" && message.contains("declined")) {
                return String(localized: "details.recent.denied", defaultValue: "Denied")
            }
            if part.errorType == "aborted" {
                return String(localized: "details.recent.stopped", defaultValue: "Stopped")
            }
            return part.exit.map(failed)
        }
        if let exit = part.exit, exit != 0 { return failed(exit) }
        return String(localized: "details.recent.done", defaultValue: "Done")
    }

    private static func failed(_ exit: Int) -> String {
        String(localized: "details.recent.failed", defaultValue: "Failed · exit \(exit)")
    }

    /// The age on the right: "now" under 45 s, then whole minutes ("2m"), hours ("3h") and days
    /// ("2d").
    static func age(of date: Date, at now: Date) -> String {
        let seconds = now.timeIntervalSince(date)
        if seconds < 45 { return String(localized: "home.summary.updated.now", defaultValue: "now") }
        let minutes = max(1, Int((seconds / 60).rounded()))
        if minutes < 60 { return "\(minutes)m" }
        let hours = minutes / 60
        if hours < 24 { return "\(hours)h" }
        return "\(hours / 24)d"
    }
}
