import Foundation

/// Where one subagent of the open session is.
nonisolated enum SessionChatSubagentStatus: Equatable, Sendable {
    case running
    /// The subagent, or a subagent it started, has a pending permission or question.
    case waitingForInput
    case done
}

/// One subagent the open session started, as the background work panel lists it.
nonisolated struct SessionChatSubagent: Equatable, Identifiable, Sendable {
    /// The subagent tool part id.
    let id: String
    /// The subagent's own session, once the server has named it.
    let childSessionID: String?
    let agentName: String
    /// What the subagent was asked to do (the tool's `description`).
    let title: String?
    let status: SessionChatSubagentStatus
    let startedAtMs: Int64?
    /// When the app saw it finish; nil for subagents that finished before the chat opened.
    let finishedAtMs: Int64?

    var isActive: Bool { status != .done }

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
    /// "3 subagents", or "general needs input · 2 running".
    let label: String
    /// A subagent waits for input: amber pill with a pulsing dot.
    let needsInput: Bool
    let accessibilityLabel: String
}

/// Subagents of the open session and the pill that sums them up. Pure: the
/// view model feeds it the loaded messages, live session activity and the
/// pending requests it already collects for the session tree.
nonisolated enum SessionChatBackgroundWork {
    /// Subagent rows in panel order: waiting, then running, then done, each in spawn order.
    static func subagents(
        messages: [Message],
        sessions: [Session],
        busySessionIDs: Set<String>,
        requestSessionIDs: [String],
        finishedAtMs: [String: Int64] = [:]
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
        let sessionsByID = Dictionary(sessions.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let childIDs = Set(tools.compactMap(childSessionID))
        let waitingChildIDs = Set(requestSessionIDs.compactMap {
            owningChild(of: $0, childIDs: childIDs, sessionsByID: sessionsByID)
        })
        let rows = tools.map { tool -> SessionChatSubagent in
            let partID = tool.id ?? ""
            let childID = childSessionID(tool)
            let session = childID.flatMap { sessionsByID[$0] }
            let status: SessionChatSubagentStatus =
                if let childID, waitingChildIDs.contains(childID) {
                    .waitingForInput
                } else if tool.status == .running || tool.status == .pending
                    || childID.map(busySessionIDs.contains) == true {
                    .running
                } else {
                    .done
                }
            return SessionChatSubagent(
                id: partID,
                childSessionID: childID,
                agentName: agentName(tool, session: session),
                title: string(tool.input, "description") ?? nonEmpty(session?.title),
                status: status,
                startedAtMs: tool.timeStartEpochMs,
                finishedAtMs: status == .done ? finishedAtMs[partID] : nil
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

    /// The pill for these rows; nil when no subagent is working or waiting.
    static func pill(for subagents: [SessionChatSubagent]) -> SessionChatBackgroundWorkPill? {
        let waiting = subagents.filter { $0.status == .waitingForInput }
        let running = subagents.filter { $0.status == .running }.count
        guard let firstWaiting = waiting.first else {
            guard running > 0 else { return nil }
            let label = subagentCount(running)
            return SessionChatBackgroundWorkPill(label: label, needsInput: false, accessibilityLabel: "\(label) running")
        }
        let asking = waiting.count == 1
            ? "\(firstWaiting.agentName) needs input"
            : "\(subagentCount(waiting.count)) need input"
        let label = running > 0 ? "\(asking) · \(running) running" : asking
        let spoken = running > 0 ? "\(asking), \(subagentCount(running)) running" : asking
        return SessionChatBackgroundWorkPill(label: label, needsInput: true, accessibilityLabel: spoken)
    }

    /// Remembers when subagents seen working finish, so their rows keep a frozen run time.
    /// Entries for parts no longer listed are dropped.
    static func finishTimes(
        previous: [SessionChatSubagent],
        current: [SessionChatSubagent],
        known: [String: Int64],
        nowMs: Int64
    ) -> [String: Int64] {
        let wasActive = Set(previous.filter(\.isActive).map(\.id))
        let currentIDs = Set(current.map(\.id))
        var next = known.filter { currentIDs.contains($0.key) }
        for row in current {
            if row.isActive {
                next[row.id] = nil
            } else if next[row.id] == nil, wasActive.contains(row.id) {
                next[row.id] = nowMs
            }
        }
        return next
    }

    private static func subagentCount(_ count: Int) -> String {
        count == 1 ? "1 subagent" : "\(count) subagents"
    }

    private static func rank(_ status: SessionChatSubagentStatus) -> Int {
        switch status {
        case .waitingForInput: 0
        case .running: 1
        case .done: 2
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
