import Foundation

/// Where one subagent of the open session is.
nonisolated enum SessionChatSubagentStatus: Equatable, Sendable {
    case running
    /// The subagent, or a subagent it started, has a pending permission or question.
    case waitingForInput
    case done
    /// The subagent's call failed.
    case failed
    /// The user stopped the run (or declined the call) while the subagent worked.
    case stopped
}

/// One subagent the open session started, as the background work panel lists it.
nonisolated struct SessionChatSubagent: Equatable, Identifiable, Sendable {
    /// The id of the subagent's latest tool part (a resumed subagent keeps one row).
    let id: String
    /// The subagent's own session, once the server has named it.
    let childSessionID: String?
    let agentName: String
    /// What the subagent was asked to do (the tool's `description`).
    let title: String?
    let status: SessionChatSubagentStatus
    let startedAtMs: Int64?
    /// When it finished: server time when known, else when the app saw it finish live; nil when unknown.
    let finishedAtMs: Int64?
    /// The agent as a small row tag; nil for the default `general` agent or an unknown one.
    let agentTag: String?
    /// The latest child tool call ("Reading format.ts"); nil when the server sends no child tool summary.
    let currentStep: String?
    /// Child tool calls so far; nil when the server sends no child tool summary.
    let toolCount: Int?
    /// Done: the first line of the result. Failed or stopped: the reason. Nil when unknown.
    let result: String?

    init(
        id: String,
        childSessionID: String?,
        agentName: String,
        title: String?,
        status: SessionChatSubagentStatus,
        startedAtMs: Int64?,
        finishedAtMs: Int64?,
        agentTag: String? = nil,
        currentStep: String? = nil,
        toolCount: Int? = nil,
        result: String? = nil
    ) {
        self.id = id
        self.childSessionID = childSessionID
        self.agentName = agentName
        self.title = title
        self.status = status
        self.startedAtMs = startedAtMs
        self.finishedAtMs = finishedAtMs
        self.agentTag = agentTag
        self.currentStep = currentStep
        self.toolCount = toolCount
        self.result = result
    }

    var isActive: Bool { status == .running || status == .waitingForInput }

    var letter: String { SessionChatChildSessionElapsed.letter(from: agentName) }

    /// Live time while it works, the real run time once done; nil when unknown or under a
    /// second (a call that failed at once never ran), so a done row never reads `0:00`.
    func elapsed(nowMs: Int64) -> String? {
        if isActive {
            return SessionChatChildSessionElapsed.format(startEpochMs: startedAtMs, nowEpochMs: nowMs)
        }
        guard let start = ToolStartTime.normalizeEpochMs(startedAtMs),
              let finished = ToolStartTime.normalizeEpochMs(finishedAtMs),
              finished - start >= 1_000
        else { return nil }
        return SessionChatChildSessionElapsed.format(startEpochMs: start, nowEpochMs: finished)
    }

    /// When a finished subagent ended: "now", "4m ago"; nil while active or when unknown.
    func finishedAgo(nowMs: Int64) -> String? {
        guard !isActive, let finished = ToolStartTime.normalizeEpochMs(finishedAtMs) else { return nil }
        return RelativeTimeFormatting.format(epochMs: finished, nowEpochMs: nowMs)
    }
}

/// What a background work panel row says, in product words.
nonisolated struct SessionChatSubagentRowText: Equatable, Sendable {
    /// The task title; the agent name when the call named no task.
    let title: String
    let tag: String?
    /// Running: the current step ("Waiting for input" while it waits). Done: the result or failure reason.
    let detail: String?
    /// Running: "12 tools". Done: when it finished, "4m ago".
    let detailCaption: String?
    /// Running: the live timer. Done: the real run time; nil when unknown.
    let time: String?
}

/// A tab of the background work panel. The panel opens on `.running`.
nonisolated enum SessionChatBackgroundWorkTab: String, Equatable, Sendable {
    case running
    case done
}

/// The background work panel split into its two tabs.
nonisolated struct SessionChatBackgroundWorkTabs: Equatable, Sendable {
    /// Waiting, then running subagents, each in spawn order.
    let running: [SessionChatSubagent]
    /// Finished, failed and stopped subagents, newest first.
    let done: [SessionChatSubagent]
}

/// What a task part says about its child's tool calls: how many, and the latest as a step title.
nonisolated struct SessionChatChildToolSummary: Equatable, Sendable {
    let count: Int
    let currentStep: String?
}

/// What the background work pill says.
nonisolated struct SessionChatBackgroundWorkPill: Equatable, Sendable {
    /// "3 subagents", "1 subagent · 2 shells", or "general needs input · 2 running".
    let label: String
    /// A subagent waits for input: amber pill with a pulsing dot.
    let needsInput: Bool
    let accessibilityLabel: String
}

/// The waiting request the background work pill opens when a subagent needs input.
nonisolated enum SessionChatInboxTarget: Equatable, Sendable {
    case permission(requestID: String)
    case question(requestID: String)
}

/// Subagents of the open session and the pill that sums them up. Pure: the
/// view model feeds it the loaded messages, live session activity and the
/// pending requests it already collects for the session tree.
nonisolated enum SessionChatBackgroundWork {
    /// Subagent rows in panel order: waiting, then running, then finished, each in spawn order.
    ///
    /// One row per child session: a resumed subagent (v1 `task_id`, or a resume the linker maps
    /// through `input.sessionID`) adds a tool part for the same child, and only its latest part
    /// counts, so the child's live state is never applied to an earlier, finished call.
    /// `localFinishedAtMs` holds finishes the app saw live; server times win over them.
    static func subagents(
        messages: [Message],
        sessions: [Session],
        busySessionIDs: Set<String>,
        requestSessionIDs: [String],
        localFinishedAtMs: [String: Int64] = [:]
    ) -> [SessionChatSubagent] {
        let tools = messages.flatMap { message in
            message.parts.compactMap { part -> ToolPart? in
                guard case .tool(let tool) = part,
                      tool.id != nil,
                      SessionChatChildSessionLinker.isSubagentTool(tool.toolName)
                else { return nil }
                return tool
            }
        }
        guard !tools.isEmpty else { return [] }
        let latestIndexByChild = Dictionary(
            tools.enumerated().compactMap { index, tool in childSessionID(tool).map { ($0, index) } },
            uniquingKeysWith: { _, later in later }
        )
        let latestTools = tools.enumerated().filter { index, tool in
            guard let childID = childSessionID(tool) else { return true }
            return latestIndexByChild[childID] == index
        }.map(\.element)
        let sessionsByID = Dictionary(sessions.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let childIDs = Set(latestIndexByChild.keys)
        let waitingChildIDs = Set(requestSessionIDs.compactMap {
            owningChild(of: $0, childIDs: childIDs, sessionsByID: sessionsByID)
        })
        let rows = latestTools.map { tool -> SessionChatSubagent in
            let partID = tool.id ?? ""
            let childID = childSessionID(tool)
            let session = childID.flatMap { sessionsByID[$0] }
            let status: SessionChatSubagentStatus =
                if tool.status == .error {
                    tool.isInterrupted ? .stopped : .failed
                } else if let childID, waitingChildIDs.contains(childID) {
                    .waitingForInput
                } else if tool.status == .running || tool.status == .pending
                    || childID.map(busySessionIDs.contains) == true {
                    .running
                } else {
                    .done
                }
            let isActive = status == .running || status == .waitingForInput
            let summary = childToolSummary(tool)
            return SessionChatSubagent(
                id: partID,
                childSessionID: childID,
                agentName: agentName(tool, session: session),
                title: string(tool.input, "description") ?? nonEmpty(session?.title),
                status: status,
                startedAtMs: tool.timeStartEpochMs,
                finishedAtMs: isActive ? nil : finishTime(tool, session: session, local: localFinishedAtMs[partID]),
                agentTag: agentTag(knownAgentName(tool, session: session)),
                currentStep: isActive ? summary?.currentStep : nil,
                toolCount: summary?.count,
                result: isActive ? nil : result(tool, status: status)
            )
        }
        return rows.enumerated()
            .sorted { lhs, rhs in
                let left = rank(lhs.element.status)
                let right = rank(rhs.element.status)
                return left == right ? lhs.offset < rhs.offset : left < right
            }
            .map(\.element)
    }

    /// Server end time first. A background call settles at spawn while its child keeps
    /// working, so its own end time is skipped; the child session's last update stands in.
    /// A finish the app saw live is the last resort. Times at or before the start are ignored,
    /// so a settle stamped with the start time never reads as a `0:00` run.
    static func finishTime(_ tool: ToolPart, session: Session?, local: Int64?) -> Int64? {
        let start = ToolStartTime.normalizeEpochMs(tool.timeStartEpochMs)
        let candidates = [
            isBackground(tool) ? nil : tool.timeEndEpochMs,
            session?.updatedAtEpochMs,
            local,
        ]
        return candidates
            .compactMap { ToolStartTime.normalizeEpochMs($0) }
            .first { candidate in start.map { candidate > $0 } ?? true }
    }

    /// The panel tabs: Running holds waiting and running subagents in panel order, Done the
    /// finished, failed and stopped ones, newest first (unknown finish times last, latest
    /// spawned first among them).
    static func tabs(_ subagents: [SessionChatSubagent]) -> SessionChatBackgroundWorkTabs {
        let done = subagents.enumerated()
            .filter { !$0.element.isActive }
            .sorted { lhs, rhs in
                let left = ToolStartTime.normalizeEpochMs(lhs.element.finishedAtMs) ?? .min
                let right = ToolStartTime.normalizeEpochMs(rhs.element.finishedAtMs) ?? .min
                return left == right ? lhs.offset > rhs.offset : left > right
            }
            .map(\.element)
        return SessionChatBackgroundWorkTabs(running: subagents.filter(\.isActive), done: done)
    }

    /// Done rows the user has seen, for the Done tab's dot. The first rows of an open Session
    /// Chat count as seen (nil until any row shows), and so does everything while the Done
    /// tab is on screen (`viewingDone`). A row that went back to work leaves the set, so its
    /// next finish dots again.
    static func seenDoneIDs(
        previous: Set<String>?,
        subagents: [SessionChatSubagent],
        viewingDone: Bool
    ) -> Set<String>? {
        if previous == nil, subagents.isEmpty { return nil }
        let doneIDs = Set(subagents.filter { !$0.isActive }.map(\.id))
        guard let previous, !viewingDone else { return doneIDs }
        return previous.intersection(doneIDs)
    }

    /// Whether a subagent finished since the Done tab was last viewed.
    static func hasUnseenDone(_ subagents: [SessionChatSubagent], seenDoneIDs: Set<String>?) -> Bool {
        guard let seenDoneIDs else { return false }
        return subagents.contains { !$0.isActive && !seenDoneIDs.contains($0.id) }
    }

    /// The row copy for `subagent` at `nowMs`. Bits the server did not send stay nil.
    static func rowText(_ subagent: SessionChatSubagent, nowMs: Int64) -> SessionChatSubagentRowText {
        let title = subagent.title.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
            ?? subagent.agentName
        let time = subagent.elapsed(nowMs: nowMs)
        guard subagent.isActive else {
            return SessionChatSubagentRowText(
                title: title,
                tag: subagent.agentTag,
                detail: subagent.result,
                detailCaption: subagent.finishedAgo(nowMs: nowMs),
                time: time
            )
        }
        return SessionChatSubagentRowText(
            title: title,
            tag: subagent.agentTag,
            detail: subagent.status == .waitingForInput ? waitingDetail : subagent.currentStep,
            detailCaption: subagent.toolCount.map(toolCountLabel),
            time: time
        )
    }

    /// "1 tool", "12 tools".
    static func toolCountLabel(_ count: Int) -> String {
        String(localized: "session_chat_sheet.background_work.tool_count", defaultValue: "\(count) tools")
    }

    /// The agent tag rule: shown only for a named agent other than the default `general`.
    static func agentTag(_ agentName: String?) -> String? {
        guard let name = agentName?.trimmingCharacters(in: .whitespaces), !name.isEmpty,
              name.lowercased() != defaultAgent
        else { return nil }
        return name
    }

    /// Done: the first line of the task output (v1 `<task_result>`, v2 `<subagent>` body), or
    /// none for a background call, whose output only says it started. Failed or stopped: the
    /// error without its `Subagent failed (task_id: …):` lead, else "Stopped".
    static func result(_ tool: ToolPart, status: SessionChatSubagentStatus) -> String? {
        switch status {
        case .done:
            isBackground(tool) ? nil : SessionChatSubagentResult.firstLine(tool.output)
        case .failed, .stopped:
            SessionChatSubagentResult.reason(tool.error) ?? (status == .stopped ? stoppedReason : nil)
        case .running, .waitingForInput:
            nil
        }
    }

    /// The child tool calls a task part reports in `metadata.summary` (classic v1 up to 1.1.x
    /// sends the child's tool parts there; later v1 and v2 send none): the count and the
    /// latest call as a step title. Nil when the part carries no summary.
    static func childToolSummary(_ tool: ToolPart) -> SessionChatChildToolSummary? {
        guard case let .array(summary)? = tool.metadata?["summary"] else { return nil }
        let calls = summary.enumerated().compactMap { index, element -> ToolPart? in
            guard case let .dictionary(part) = element, let name = string(part, "tool") else { return nil }
            var state: [String: AnyCodable]?
            if case let .dictionary(value)? = part["state"] { state = value }
            var input: [String: AnyCodable]?
            if case let .dictionary(value)? = state?["input"] { input = value }
            let status: ToolStatus =
                switch string(state, "status") {
                case "pending": .pending
                case "running": .running
                case "error": .error
                default: .completed
                }
            return ToolPart(
                id: string(part, "id") ?? "summary-\(index)",
                toolName: name,
                status: status,
                title: string(state, "title"),
                input: input
            )
        }
        guard !calls.isEmpty else { return nil }
        // The activity card's rule: the latest running call's running title, else the last call.
        let activity = SessionChatTimelineBlocks.childActivity(from: [
            Message(id: "summary", role: .assistant, text: "", parts: calls.map { .tool($0) }, model: nil, mode: nil, sentAtEpochMs: nil),
        ])
        let step = activity.currentAction ?? activity.recentSteps.last?.title
        return SessionChatChildToolSummary(count: calls.count, currentStep: nonEmpty(step?.resolved))
    }

    /// The pill for these rows and the open session's shells; nil when nothing works or waits.
    /// Running shells count like running subagents: "1 subagent · 2 shells", "1 shell".
    static func pill(
        for subagents: [SessionChatSubagent],
        shells: [AgentShell] = []
    ) -> SessionChatBackgroundWorkPill? {
        let waiting = subagents.filter { $0.status == .waitingForInput }
        let runningSubagents = subagents.filter { $0.status == .running }.count
        let runningShells = shells.filter(\.status.isRunning).count
        let running = runningSubagents + runningShells
        guard let firstWaiting = waiting.first else {
            guard running > 0 else { return nil }
            let spoken = spokenCount(subagents: runningSubagents, shells: runningShells)
            return SessionChatBackgroundWorkPill(
                label: countLabel(subagents: runningSubagents, shells: runningShells),
                needsInput: false,
                accessibilityLabel: String(
                    localized: "timeline.background_work.running_spoken",
                    defaultValue: "\(spoken) running"
                )
            )
        }
        let asking = askingLabel(waiting: waiting.count, firstAgent: firstWaiting.agentName)
        guard running > 0 else {
            return SessionChatBackgroundWorkPill(label: asking, needsInput: true, accessibilityLabel: asking)
        }
        let spoken = spokenCount(subagents: runningSubagents, shells: runningShells)
        let label = String(
            localized: "timeline.background_work.needs_input_running",
            defaultValue: "\(asking) · \(running) running"
        )
        let accessibilityLabel = String(
            localized: "timeline.background_work.needs_input_running_spoken",
            defaultValue: "\(asking), \(spoken) running"
        )
        return SessionChatBackgroundWorkPill(label: label, needsInput: true, accessibilityLabel: accessibilityLabel)
    }

    /// "general needs input" (the agent name is server content), or "2 subagents need input".
    private static func askingLabel(waiting: Int, firstAgent: String) -> String {
        guard waiting > 1 else {
            return String(
                localized: "timeline.background_work.agent_needs_input",
                defaultValue: "\(firstAgent) needs input"
            )
        }
        return String(
            localized: "timeline.background_work.subagents_need_input",
            defaultValue: "\(waiting) subagents need input"
        )
    }

    /// Running shells of the panel's Shells section, in start order.
    static func runningShells(_ shells: [AgentShell]) -> [AgentShell] {
        shells.enumerated()
            .filter { $0.element.status.isRunning }
            .sorted { lhs, rhs in
                let left = lhs.element.startedAtMs ?? .max
                let right = rhs.element.startedAtMs ?? .max
                return left == right ? lhs.offset < rhs.offset : left < right
            }
            .map(\.element)
    }

    /// The request the needs-input pill pages to: the first waiting subagent's permission,
    /// else its question. A question waits behind any permission (the permission panel
    /// shows first), so then the shown permission opens instead. Nil when no subagent waits
    /// or its request is gone, so the tap falls back to the subagents panel.
    static func needsInputTarget(
        subagents: [SessionChatSubagent],
        sessions: [Session],
        permissions: [PermissionRequest],
        questions: [QuestionRequest],
        shownPermissionID: String?
    ) -> SessionChatInboxTarget? {
        guard let childID = subagents.first(where: { $0.status == .waitingForInput })?.childSessionID else {
            return nil
        }
        let childIDs = Set(subagents.compactMap(\.childSessionID))
        let sessionsByID = Dictionary(sessions.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        func asks(_ sessionID: String) -> Bool {
            owningChild(of: sessionID, childIDs: childIDs, sessionsByID: sessionsByID) == childID
        }
        if let permission = permissions.first(where: { asks($0.sessionID) }) {
            return .permission(requestID: permission.id)
        }
        guard let question = questions.first(where: { asks($0.sessionID) }) else { return nil }
        guard !permissions.isEmpty else { return .question(requestID: question.id) }
        return .permission(requestID: shownPermissionID ?? permissions[0].id)
    }

    /// Remembers when subagents finish while the app watches live, for rows without a server
    /// finish time. `seenLive` is false when the previous rows predate the current live
    /// connection (backgrounded, offline, reconnect refresh): the finish happened at some
    /// unknown point while the app was away, so nothing is stamped.
    static func finishTimes(
        previous: [SessionChatSubagent],
        current: [SessionChatSubagent],
        known: [String: Int64],
        nowMs: Int64,
        seenLive: Bool
    ) -> [String: Int64] {
        let wasActive = Set(previous.filter(\.isActive).map(\.id))
        let currentIDs = Set(current.map(\.id))
        var next = known.filter { currentIDs.contains($0.key) }
        for row in current {
            if row.isActive {
                next[row.id] = nil
            } else if seenLive, row.finishedAtMs == nil, next[row.id] == nil, wasActive.contains(row.id) {
                next[row.id] = nowMs
            }
        }
        return next
    }

    private static func subagentCount(_ count: Int) -> String {
        String(localized: "timeline.background_work.subagent_count", defaultValue: "\(count) subagents")
    }

    private static func shellCount(_ count: Int) -> String {
        String(localized: "timeline.background_work.shell_count", defaultValue: "\(count) shells")
    }

    /// "1 subagent · 2 shells", "2 shells", "3 subagents".
    private static func countLabel(subagents: Int, shells: Int) -> String {
        guard subagents > 0, shells > 0 else {
            return subagents > 0 ? subagentCount(subagents) : shellCount(shells)
        }
        let subagentText = subagentCount(subagents)
        let shellText = shellCount(shells)
        return String(
            localized: "timeline.background_work.subagents_and_shells",
            defaultValue: "\(subagentText) · \(shellText)"
        )
    }

    /// "1 subagent and 2 shells", "2 shells", "3 subagents".
    private static func spokenCount(subagents: Int, shells: Int) -> String {
        guard subagents > 0, shells > 0 else {
            return subagents > 0 ? subagentCount(subagents) : shellCount(shells)
        }
        let subagentText = subagentCount(subagents)
        let shellText = shellCount(shells)
        return String(
            localized: "timeline.background_work.subagents_and_shells_spoken",
            defaultValue: "\(subagentText) and \(shellText)"
        )
    }

    private static func rank(_ status: SessionChatSubagentStatus) -> Int {
        switch status {
        case .waitingForInput: 0
        case .running: 1
        case .done, .failed, .stopped: 2
        }
    }

    /// The subagent child whose session tree holds `sessionID`, walking up `parentID`.
    private static func owningChild(
        of sessionID: String,
        childIDs: Set<String>,
        sessionsByID: [String: Session]
    ) -> String? {
        var current: String? = sessionID
        var seen: Set<String> = []
        while let id = current, seen.insert(id).inserted {
            if childIDs.contains(id) { return id }
            current = sessionsByID[id]?.parentID
        }
        return nil
    }

    /// Tool input names the agent on both protocols; the session `agent` field
    /// (v2) or the v1 title suffix covers the rest, like the request panels.
    private static func agentName(_ tool: ToolPart, session: Session?) -> String {
        knownAgentName(tool, session: session) ?? (tool.toolName.lowercased() == "subagent" ? "General" : "Task")
    }

    private static func knownAgentName(_ tool: ToolPart, session: Session?) -> String? {
        string(tool.input, "agent") ?? string(tool.input, "subagent_type")
            ?? string(tool.metadata, "agent")
            ?? session?.agentName
            ?? session.flatMap { InboxRequestAttribution.agentName(fromTitle: $0.title) }
    }

    private static let defaultAgent = "general"
    private static var stoppedReason: String {
        String(localized: "session_chat_sheet.background_work.stopped", defaultValue: "Stopped")
    }

    private static var waitingDetail: String {
        String(localized: "session_chat_sheet.background_work.waiting_for_input", defaultValue: "Waiting for input")
    }

    private static func isBackground(_ tool: ToolPart) -> Bool {
        switch tool.metadata?["background"] {
        case .bool(let flag)?: flag
        case .string(let text)?: text.lowercased() == "true" || text == "1"
        default: false
        }
    }

    private static func childSessionID(_ tool: ToolPart) -> String? {
        string(tool.metadata, "sessionID") ?? string(tool.metadata, "sessionId")
    }

    private static func string(_ values: [String: AnyCodable]?, _ key: String) -> String? {
        guard case let .string(value)? = values?[key] else { return nil }
        return nonEmpty(value)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}

/// Result and failure lines of a subagent's task part.
nonisolated enum SessionChatSubagentResult {
    /// First meaningful line of a task output: inside `<task_result>` or `<task_error>` when
    /// present, skipping the `task_id:` header, wrapper tags such as `<subagent sessionID="…">`
    /// and Markdown markers.
    static func firstLine(_ output: String?) -> String? {
        guard let output, !output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        var body = Substring(output)
        for tag in ["task_result", "task_error"] {
            guard let open = body.range(of: "<\(tag)>") else { continue }
            body = body[open.upperBound...]
            if let close = body.range(of: "</\(tag)>") { body = body[..<close.lowerBound] }
            break
        }
        return body
            .split(whereSeparator: \.isNewline)
            .lazy
            .map { line -> String in
                var trimmed = line.trimmingCharacters(in: .whitespaces)
                while let first = trimmed.first, "#*- ".contains(first) { trimmed.removeFirst() }
                while trimmed.last == "*" { trimmed.removeLast() }
                return trimmed
            }
            .first { !$0.isEmpty && !$0.hasPrefix("task_id:") && !$0.hasPrefix("<") }
    }

    /// A failure as one line: "Subagent failed (task_id: ses_1): Rate limited" reads "Rate
    /// limited", and "Subagent cancelled (sessionID: ses_1)" reads "Subagent cancelled".
    static func reason(_ error: String?) -> String? {
        guard let line = firstLine(error) else { return nil }
        if let failed = line.range(of: #"^Subagent failed \((?:task_id|sessionID): [^)]*\):"#, options: .regularExpression) {
            let rest = line[failed.upperBound...].trimmingCharacters(in: .whitespaces)
            return rest.isEmpty ? line : rest
        }
        let cleaned = line
            .replacingOccurrences(of: #"\s*\((?:task_id|sessionID): [^)]*\)"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        return cleaned.isEmpty ? nil : cleaned
    }
}
