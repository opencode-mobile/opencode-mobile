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

    var isActive: Bool { status == .running || status == .waitingForInput }

    var letter: String { SessionChatChildSessionElapsed.letter(from: agentName) }

    /// Live time while it works, the frozen run time once done, nil when unknown.
    func elapsed(nowMs: Int64) -> String? {
        if isActive {
            return SessionChatChildSessionElapsed.format(startEpochMs: startedAtMs, nowEpochMs: nowMs)
        }
        guard let finishedAtMs else { return nil }
        return SessionChatChildSessionElapsed.format(startEpochMs: startedAtMs, nowEpochMs: finishedAtMs)
    }
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
            return SessionChatSubagent(
                id: partID,
                childSessionID: childID,
                agentName: agentName(tool, session: session),
                title: string(tool.input, "description") ?? nonEmpty(session?.title),
                status: status,
                startedAtMs: tool.timeStartEpochMs,
                finishedAtMs: isActive ? nil : finishTime(tool, session: session, local: localFinishedAtMs[partID])
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
    /// A finish the app saw live is the last resort. Times before the start are ignored.
    static func finishTime(_ tool: ToolPart, session: Session?, local: Int64?) -> Int64? {
        let start = ToolStartTime.normalizeEpochMs(tool.timeStartEpochMs) ?? 0
        let candidates = [
            isBackground(tool) ? nil : tool.timeEndEpochMs,
            session?.updatedAtEpochMs,
            local,
        ]
        return candidates
            .compactMap { ToolStartTime.normalizeEpochMs($0) }
            .first { $0 >= start }
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
            let label = [
                runningSubagents > 0 ? subagentCount(runningSubagents) : nil,
                runningShells > 0 ? shellCount(runningShells) : nil,
            ].compactMap { $0 }.joined(separator: " · ")
            return SessionChatBackgroundWorkPill(
                label: label,
                needsInput: false,
                accessibilityLabel: "\(spokenCount(subagents: runningSubagents, shells: runningShells)) running"
            )
        }
        let asking = waiting.count == 1
            ? "\(firstWaiting.agentName) needs input"
            : "\(subagentCount(waiting.count)) need input"
        let label = running > 0 ? "\(asking) · \(running) running" : asking
        let spoken = running > 0
            ? "\(asking), \(spokenCount(subagents: runningSubagents, shells: runningShells)) running"
            : asking
        return SessionChatBackgroundWorkPill(label: label, needsInput: true, accessibilityLabel: spoken)
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
        count == 1 ? "1 subagent" : "\(count) subagents"
    }

    private static func shellCount(_ count: Int) -> String {
        count == 1 ? "1 shell" : "\(count) shells"
    }

    /// "1 subagent and 2 shells", "2 shells", "3 subagents".
    private static func spokenCount(subagents: Int, shells: Int) -> String {
        [
            subagents > 0 ? subagentCount(subagents) : nil,
            shells > 0 ? shellCount(shells) : nil,
        ].compactMap { $0 }.joined(separator: " and ")
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
        string(tool.input, "agent") ?? string(tool.input, "subagent_type")
            ?? string(tool.metadata, "agent")
            ?? session?.agentName
            ?? session.flatMap { InboxRequestAttribution.agentName(fromTitle: $0.title) }
            ?? (tool.toolName.lowercased() == "subagent" ? "General" : "Task")
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
