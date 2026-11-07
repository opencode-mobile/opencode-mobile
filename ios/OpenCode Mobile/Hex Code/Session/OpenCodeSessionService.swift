import Foundation
import OpenCodeKit

/// What following today's session asks of the server, beyond setting it up.
protocol LiveSessionAPI: Sendable {
    /// The state on a (re)connect: busy since when, the subagents, what they wait for, the inbox,
    /// the to-do list.
    func snapshot(rootID: String) async throws -> LiveSnapshot
    func generate(sessionID: String, prompt: String) async throws -> String
    func switchModel(sessionID: String, model: ModelChoice) async throws
    /// The session's `time.updated` (epoch ms).
    func sessionUpdated(sessionID: String) async throws -> Int64
    /// Sends a message with its images; returns its inbox id.
    func prompt(sessionID: String, text: String, images: [OutgoingImage], delivery: Delivery) async throws -> String
    /// Takes back a message not delivered yet.
    func cancelInbox(sessionID: String, itemID: String) async throws
}

/// How a message reaches the agent: after this turn, or at the next step (and at once when idle).
enum Delivery: String, Equatable, Sendable {
    case queue
    case steer
}

/// The event stream's transport: one connection, until it ends or is cancelled.
protocol EventStreamTransport: Sendable {
    /// Calls `onActivity` for every line (heartbeats too) and `onLine` for every `data:` payload.
    func stream(
        types: [String],
        onActivity: @escaping @Sendable () async -> Void,
        onLine: @escaping @Sendable (String) async -> Void
    ) async throws -> SseAttemptOutcome
}

/// Today's session on a real OpenCode v2 server, through OpenCodeKit's `SessionStateAPIClient`.
struct OpenCodeSessionService: DailySessionService, LiveSessionAPI {
    let client: SessionStateAPIClient

    init(connection: ServerConnection, urlSession: URLSession? = nil) {
        client = SessionStateAPIClient(fixedConnection: connection, urlSession: urlSession)
    }

    // MARK: DailySessionService

    func sessionsPage(directory: String, search: String, limit: Int, cursor: String?) async throws -> SessionListingPage {
        let page = try await client.listSessions(
            directory: directory,
            search: search,
            parent: SessionStateAPIClient.Parent.none,
            archived: false,
            limit: limit,
            cursor: cursor
        )
        return SessionListingPage(sessions: page.sessions.map(Self.listing), next: page.nextCursor)
    }

    func createSession(title: String, directory: String, model: ModelChoice) async throws -> SessionListing {
        Self.listing(try await client.createSession(title: title, directory: directory, model: Self.ref(model)))
    }

    func putMessageRules(sessionID: String) async throws {
        try await client.putInstructionEntry(
            sessionID: sessionID,
            key: SessionPrompts.MessageRules.entryID,
            value: SessionPrompts.MessageRules.text
        )
    }

    // MARK: LiveSessionAPI

    func snapshot(rootID: String) async throws -> LiveSnapshot {
        async let active = client.activeSessionIDs()
        async let root = client.getSession(id: rootID)
        async let children = client.listSessions(parent: .session(rootID))
        async let inbox = client.inbox(sessionID: rootID)
        async let todos = todos(sessionID: rootID)
        let running = try await active
        let session = try await root
        let childList = try await children.sessions
        let childIDs = childList.map(\.id)
        // Pending items of today's session and each subagent that is running.
        var pending: [PendingItem] = []
        for id in [rootID] + childIDs.filter(running.contains) {
            async let permissions = client.pendingPermissions(sessionID: id)
            async let forms = client.pendingForms(sessionID: id)
            pending += try await forms.map(PendingItem.init(question:)) + permissions.map(PendingItem.init(approval:))
        }
        let busy = running.contains(rootID)
            ? Date(timeIntervalSince1970: Double(session.busyMs ?? Int64(Date().timeIntervalSince1970 * 1000)) / 1000)
            : nil
        return LiveSnapshot(
            busySince: busy,
            hasRun: session.busyMs != nil,
            children: childIDs,
            updated: session.updatedMs,
            runningChildren: childIDs.filter(running.contains),
            pending: PendingItem.oldestFirst(pending),
            inbox: try await inbox.map {
                InboxEntry(
                    id: $0.id,
                    delivery: $0.delivery ?? "queue",
                    text: $0.text ?? "",
                    files: $0.files,
                    kind: $0.type ?? "user",
                    source: $0.metadata?.source,
                    goalID: $0.metadata?.goalID
                )
            },
            // A list that can't be read doesn't fail the rest; the last one stays.
            todos: try? await todos.map(TodoList.items) ?? [],
            childSessions: Self.childSessions(childList),
            childBusySince: Self.busySince(childList.filter { running.contains($0.id) }),
            projectID: session.projectID
        )
    }

    static func childSessions(_ list: [SessionInfoV2]) -> [String: ChildSession] {
        Dictionary(list.map { ($0.id, ChildSession(title: $0.title, agent: $0.agent)) }) { first, _ in first }
    }

    /// When each session's latest run started; now for one running without `time.busy`.
    static func busySince(_ list: [SessionInfoV2]) -> [String: Date] {
        Dictionary(list.map { ($0.id, $0.busyMs.map { Date(timeIntervalSince1970: Double($0) / 1000) } ?? Date()) }) { first, _ in first }
    }

    /// Pages read looking for the newest `todowrite` call, newest first.
    static let todoPages = 4
    static let todoPageSize = 50

    /// The newest completed `todowrite` list in the session; nil when there is none.
    func todos(sessionID: String) async throws -> [TodoItemV2]? {
        var cursor: String?
        for _ in 0 ..< Self.todoPages {
            let page = try await client.assistantMessages(sessionID: sessionID, limit: Self.todoPageSize, cursor: cursor)
            if let todos = TodoList.newest(in: page.messages) { return todos }
            guard let next = page.nextCursor, !page.messages.isEmpty else { return nil }
            cursor = next
        }
        return nil
    }

    func generate(sessionID: String, prompt: String) async throws -> String {
        try await client.generate(sessionID: sessionID, prompt: prompt, timeout: 60)
    }

    func switchModel(sessionID: String, model: ModelChoice) async throws {
        try await client.switchModel(sessionID: sessionID, model: Self.ref(model))
    }

    func sessionUpdated(sessionID: String) async throws -> Int64 {
        try await client.getSession(id: sessionID).updatedMs
    }

    /// The images travel inside the prompt as `data:` URLs (app plan, "Attach" › Sending), read
    /// from the phone's cache here, off the main thread.
    func prompt(sessionID: String, text: String, images: [OutgoingImage], delivery: Delivery) async throws -> String {
        let files = try await Task.detached(priority: .userInitiated) {
            try images.map { SessionStateAPIClient.PromptFile(uri: try $0.image.dataURL(), name: $0.name) }
        }.value
        return try await client.prompt(sessionID: sessionID, text: text, delivery: delivery == .queue ? .queue : .steer, files: files).id
    }

    func cancelInbox(sessionID: String, itemID: String) async throws {
        try await client.cancelInbox(sessionID: sessionID, itemID: itemID)
    }

    // MARK: Mapping

    static func ref(_ model: ModelChoice) -> SessionModelRefV2 {
        SessionModelRefV2(providerID: model.providerID, id: model.modelID, variant: model.variant)
    }

    static func listing(_ info: SessionInfoV2) -> SessionListing {
        SessionListing(
            id: info.id,
            title: info.title ?? "",
            directory: info.directory ?? "",
            updatedAt: Date(timeIntervalSince1970: Double(info.updatedMs) / 1000),
            busySince: info.busyMs.map { Date(timeIntervalSince1970: Double($0) / 1000) }
        )
    }

    static func approval(_ permission: PendingPermissionV2) -> PendingItem { PendingItem(approval: permission) }

    static func question(_ form: PendingFormV2) -> PendingItem { PendingItem(question: form) }
}

// MARK: SessionControlAPI

extension OpenCodeSessionService: SessionControlAPI {
    func replyToApproval(sessionID: String, requestID: String, decision: SessionStateAPIClient.PermissionDecision, note: String?) async throws {
        try await Self.mapGone { try await client.replyToPermission(sessionID: sessionID, requestID: requestID, decision: decision, message: note) }
    }

    func answerQuestion(sessionID: String, formID: String, answer: [String: FormAnswerV2]) async throws {
        try await Self.mapGone { try await client.replyToForm(sessionID: sessionID, formID: formID, answer: answer) }
    }

    func dismissQuestion(sessionID: String, formID: String, note: String) async throws {
        try await Self.mapGone { try await client.cancelForm(sessionID: sessionID, formID: formID, message: note) }
    }

    func interrupt(sessionID: String) async throws -> Bool {
        try await client.interrupt(sessionID: sessionID)
    }

    func runningSubagents(rootID: String) async throws -> [String] {
        async let active = client.activeSessionIDs()
        async let children = client.listSessions(parent: .session(rootID))
        let running = try await active
        return try await children.sessions.map(\.id).filter(running.contains)
    }

    func running(rootID: String, directory: String) async throws -> RunningSnapshot {
        async let active = client.activeSessionIDs()
        async let children = client.listSessions(parent: .session(rootID))
        async let shells = client.shells(directory: directory)
        let running = try await active
        let childList = try await children.sessions
        let runningChildren = childList.filter { running.contains($0.id) }
        let owners = Set([rootID] + childList.map(\.id))
        return RunningSnapshot(
            childBusySince: Self.busySince(runningChildren),
            childSessions: Self.childSessions(childList),
            shells: try await shells.compactMap { shell in
                guard shell.status == nil || shell.status == "running", let owner = shell.sessionID, owners.contains(owner) else { return nil }
                return RunningSnapshot.Shell(
                    id: shell.id,
                    sessionID: owner,
                    command: shell.command ?? "",
                    startedAt: shell.startedMs.map { Date(timeIntervalSince1970: Double($0) / 1000) } ?? Date()
                )
            }
        )
    }

    func recentTools(sessionID: String) async throws -> [ToolPartV2] {
        let page = try await client.assistantMessages(sessionID: sessionID, limit: RecentSteps.messageLimit)
        return page.messages.flatMap { $0.toolParts.reversed() }
    }

    /// A request the server no longer has (`404`), or a form already settled (`409`), is gone.
    private static func mapGone(_ call: () async throws -> Void) async throws {
        do {
            try await call()
        } catch let SessionStateAPIError.httpStatus(status, _) where status == 404 || status == 409 {
            throw ControlError.gone
        }
    }
}

/// The summary from today's session's own generate route.
struct SessionSummaryGenerator: SummaryGenerating {
    let api: any LiveSessionAPI
    let sessionID: String

    func generateSummary(prompt: String) async throws -> String {
        try await api.generate(sessionID: sessionID, prompt: prompt)
    }

    func sessionUpdated() async throws -> Int64? {
        try await api.sessionUpdated(sessionID: sessionID)
    }
}

/// `GET /api/event` with the given types, through OpenCodeKit's `EventsAPIClient`.
struct OpenCodeEventTransport: EventStreamTransport {
    let client: EventsAPIClient

    init(connection: ServerConnection) {
        let v2 = connection.withProtocol(.v2)
        client = EventsAPIClient(connectionProvider: { v2 })
    }

    func stream(
        types: [String],
        onActivity: @escaping @Sendable () async -> Void,
        onLine: @escaping @Sendable (String) async -> Void
    ) async throws -> SseAttemptOutcome {
        try await client.streamEvents(path: "api/event", directory: nil, types: types, onActivity: onActivity, onDataLine: onLine)
    }
}

// MARK: GoalAPI

extension OpenCodeSessionService: GoalAPI {
    func goal(sessionID: String, directory: String) async throws -> TodayGoal? {
        try await client.goal(sessionID: sessionID, directory: directory).map(TodayGoal.init)
    }

    /// The images go as `files`, as a prompt's do; the kickoff then reaches the agent with them.
    func goalCommand(sessionID: String, text: String, images: [OutgoingImage]) async throws {
        let files = try await Task.detached(priority: .userInitiated) {
            try images.map { SessionStateAPIClient.PromptFile(uri: try $0.image.dataURL(), name: $0.name) }
        }.value
        do {
            try await client.runCommand(sessionID: sessionID, name: "goal", text: text, files: files)
        } catch let SessionStateAPIError.httpStatus(status, _) where status == 500 {
            throw GoalAPIError.stale
        }
    }
}
