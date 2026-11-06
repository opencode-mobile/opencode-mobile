import Foundation
import OpenCodeKit

/// What following today's session asks of the server, beyond setting it up.
protocol LiveSessionAPI: Sendable {
    /// The state on a (re)connect: busy since when, the subagents, what they wait for, the inbox.
    func snapshot(rootID: String) async throws -> LiveSnapshot
    func generate(sessionID: String, prompt: String) async throws -> String
    func switchModel(sessionID: String, model: ModelChoice) async throws
    /// Sends a message; returns its inbox id.
    func prompt(sessionID: String, text: String, delivery: Delivery) async throws -> String
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

    init(connection: ServerConnection) {
        client = SessionStateAPIClient(fixedConnection: connection)
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
        let running = try await active
        let session = try await root
        let childIDs = try await children.sessions.map(\.id)
        // Pending items of today's session and each subagent that is running.
        var pending: [PendingItem] = []
        for id in [rootID] + childIDs.filter(running.contains) {
            async let permissions = client.pendingPermissions(sessionID: id)
            async let forms = client.pendingForms(sessionID: id)
            pending += try await forms.map(Self.question) + permissions.map(Self.approval)
        }
        let busy = running.contains(rootID)
            ? Date(timeIntervalSince1970: Double(session.busyMs ?? Int64(Date().timeIntervalSince1970 * 1000)) / 1000)
            : nil
        return LiveSnapshot(
            busySince: busy,
            hasRun: session.busyMs != nil,
            children: childIDs,
            pending: PendingItem.oldestFirst(pending),
            inbox: try await inbox.map { InboxEntry(id: $0.id, delivery: $0.delivery ?? "queue", text: $0.text ?? "") }
        )
    }

    func generate(sessionID: String, prompt: String) async throws -> String {
        try await client.generate(sessionID: sessionID, prompt: prompt, timeout: 60)
    }

    func switchModel(sessionID: String, model: ModelChoice) async throws {
        try await client.switchModel(sessionID: sessionID, model: Self.ref(model))
    }

    func prompt(sessionID: String, text: String, delivery: Delivery) async throws -> String {
        try await client.prompt(sessionID: sessionID, text: text, delivery: delivery == .queue ? .queue : .steer).id
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

    static func approval(_ permission: PendingPermissionV2) -> PendingItem {
        let text = PendingPermissionText(action: permission.action, resources: permission.resources, message: permission.message).text
        return PendingItem(id: permission.id, sessionID: permission.sessionID, kind: .approval, text: text)
    }

    static func question(_ form: PendingFormV2) -> PendingItem {
        let fields = form.fields.map { PendingQuestionText.Field(title: $0.title, description: $0.description) }
        return PendingItem(id: form.id, sessionID: form.sessionID, kind: .question, text: PendingQuestionText(title: form.title, fields: fields).text)
    }
}

/// The summary from today's session's own generate route.
struct SessionSummaryGenerator: SummaryGenerating {
    let api: any LiveSessionAPI
    let sessionID: String

    func generateSummary(prompt: String) async throws -> String {
        try await api.generate(sessionID: sessionID, prompt: prompt)
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
