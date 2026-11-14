import Foundation
import OpenCodeKit

/// A question or approval the agent waits for, in today's session or one of its subagents.
struct PendingItem: Equatable, Sendable, Identifiable {
    enum Kind: Equatable, Sendable {
        case question
        case approval
    }

    /// The whole request or form, for its sheet.
    enum Request: Equatable, Sendable {
        case approval(PendingPermissionV2)
        case question(PendingFormV2)
    }

    var id: String
    /// The session that owns it: a subagent's own id for its requests. Replies go there.
    var sessionID: String
    var kind: Kind
    /// The question's text, or the command or action to approve (the hint card's second line).
    var text: String
    /// Nil only for an item made from its text alone.
    var request: Request?

    init(id: String, sessionID: String, kind: Kind, text: String, request: Request? = nil) {
        self.id = id
        self.sessionID = sessionID
        self.kind = kind
        self.text = text
        self.request = request
    }

    init(approval: PendingPermissionV2) {
        self.init(
            id: approval.id,
            sessionID: approval.sessionID,
            kind: .approval,
            text: PendingPermissionText(action: approval.action, resources: approval.resources, message: approval.message).text,
            request: .approval(approval)
        )
    }

    init(question: PendingFormV2) {
        let fields = question.fields.map { PendingQuestionText.Field(title: $0.title, description: $0.description) }
        self.init(
            id: question.id,
            sessionID: question.sessionID,
            kind: .question,
            text: PendingQuestionText(title: question.title, fields: fields).text,
            request: .question(question)
        )
    }

    var approval: PendingPermissionV2? {
        if case let .approval(approval) = request { return approval }
        return nil
    }

    var question: PendingFormV2? {
        if case let .question(question) = request { return question }
        return nil
    }
}

extension PendingItem {
    /// Oldest first. Neither list has a time, but ids grow with time: compare the part after the
    /// first `_` (`per_…`, `frm_…`), across approvals and questions alike.
    static func oldestFirst(_ items: [PendingItem]) -> [PendingItem] {
        items.sorted { $0.idOrder < $1.idOrder }
    }

    private var idOrder: Substring {
        guard let underscore = id.firstIndex(of: "_") else { return id[...] }
        return id[id.index(after: underscore)...]
    }
}

/// A subagent of today's session: its title (the subagent's description) and agent.
struct ChildSession: Equatable, Sendable {
    var title: String?
    var agent: String?
}

/// An agent shell: what it runs, and since when.
struct ShellEntry: Equatable, Sendable {
    var sessionID: String
    var command: String
    var startedAt: Date
}

/// One event from `GET /api/event`, with what home needs from it.
enum SessionEvent: Equatable, Sendable {
    /// The first frame of every stream.
    case connected
    case executionStarted(sessionID: String, at: Date?)
    /// `session.execution.succeeded`, `.failed` or `.interrupted`.
    case executionEnded(sessionID: String)
    case permissionAsked(PendingItem)
    case permissionReplied(sessionID: String, id: String)
    case formCreated(PendingItem)
    /// `form.replied` or `form.cancelled`.
    case formClosed(sessionID: String, id: String)
    /// A message joined the inbox; its files by name and type only, never their contents. `kind`
    /// is the item's `type`: `user`, or `synthetic` for a note the server made (a subagent's
    /// `<subagent …>` note that it finished). `metadata` says where the server's own items come
    /// from: a goal's kickoff and continuations carry `source: "goal"`.
    case inboxEnqueued(
        sessionID: String,
        id: String,
        delivery: String,
        text: String,
        files: [InboxFileV2] = [],
        kind: String = "user",
        metadata: InboxMetadataV2? = nil
    )
    /// `session.inbox.delivered`: the message reached the agent.
    case inboxDelivered(sessionID: String, id: String)
    /// `session.inbox.cancelled`.
    case inboxRemoved(sessionID: String, id: String)
    case inboxDeliveryChanged(sessionID: String, id: String, delivery: String)
    case sessionCreated(id: String, parentID: String?, title: String? = nil, agent: String? = nil)
    case shellCreated(id: String, sessionID: String, at: Date?, command: String? = nil)
    case shellExited(id: String)
    /// `session.tool.success` whose `metadata.todos` is a list: a `todowrite` call (the event
    /// doesn't name its tool).
    case todosWritten(sessionID: String, todos: [TodoItemV2])
    /// Any other `session.tool.success`, or a `session.tool.failed`: Details' Recent reads again.
    case toolFinished(sessionID: String)
    /// `rpc.opencode.goal.changed`: the session's goal after any write; nil when it was cleared.
    case goalChanged(sessionID: String, goal: TodayGoal?)

    /// The exact types home subscribes to, each sent as its own `types=` (the server refuses
    /// wildcards and lists).
    static let types = [
        "session.execution.started",
        "session.execution.succeeded",
        "session.execution.failed",
        "session.execution.interrupted",
        "permission.asked",
        "permission.replied",
        "form.created",
        "form.replied",
        "form.cancelled",
        "session.inbox.enqueued",
        "session.inbox.delivered",
        "session.inbox.cancelled",
        "session.inbox.delivery.changed",
        "session.created",
        "shell.created",
        "shell.exited",
        "session.tool.failed",
        "session.tool.success",
        // The artifacts setup session's live line: which tool the agent calls (only its name).
        "session.tool.input.started",
        // The server accepts any `rpc.*` name without checking it, so a typo here fails silently.
        "rpc.opencode.goal.changed",
    ]

    /// Reads one `data:` payload; nil for types home doesn't use or a payload it can't read.
    static func decode(_ line: String) -> SessionEvent? {
        guard let object = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any],
              let type = object["type"] as? String
        else { return nil }
        let data = object["data"] as? [String: Any] ?? [:]
        let created = (object["created"] as? Double).map { Date(timeIntervalSince1970: $0 / 1000) }
        let sessionID = data["sessionID"] as? String
        switch type {
        case "server.connected":
            return .connected
        case "session.execution.started":
            return sessionID.map { .executionStarted(sessionID: $0, at: created) }
        case "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted":
            return sessionID.map { .executionEnded(sessionID: $0) }
        case "permission.asked":
            // The event's data is the request itself.
            guard let request: PendingPermissionV2 = decoded(data) else { return nil }
            return .permissionAsked(PendingItem(approval: request))
        case "permission.replied":
            guard let sessionID, let id = data["requestID"] as? String else { return nil }
            return .permissionReplied(sessionID: sessionID, id: id)
        case "form.created":
            guard let form = data["form"] as? [String: Any], let request: PendingFormV2 = decoded(form) else { return nil }
            return .formCreated(PendingItem(question: request))
        case "form.replied", "form.cancelled":
            guard let sessionID, let id = data["id"] as? String else { return nil }
            return .formClosed(sessionID: sessionID, id: id)
        case "session.inbox.enqueued":
            guard let sessionID, let id = data["inboxID"] as? String else { return nil }
            let item = data["item"] as? [String: Any]
            let payload = item?["payload"] as? [String: Any]
            let text = payload?["text"] as? String ?? ""
            // Each file carries its stored base64 (about 0.7 MB a photo); only names and types are kept.
            let files = (payload?["files"] as? [[String: Any]] ?? []).map {
                InboxFileV2(name: $0["name"] as? String, mime: $0["mime"] as? String)
            }
            let metadata = payload?["metadata"] as? [String: Any]
            return .inboxEnqueued(
                sessionID: sessionID,
                id: id,
                delivery: item?["delivery"] as? String ?? "queue",
                text: text,
                files: files,
                kind: item?["type"] as? String ?? "user",
                metadata: metadata.map {
                    InboxMetadataV2(
                        source: $0["source"] as? String,
                        goalID: $0["goalID"] as? String,
                        attachments: ($0["attachments"] as? [[String: Any]] ?? []).compactMap { entry in
                            guard let path = entry["path"] as? String, !path.isEmpty else { return nil }
                            return PromptAttachment(name: entry["name"] as? String ?? "", mime: entry["mime"] as? String ?? "", path: path)
                        }
                    )
                }
            )
        case "session.inbox.delivered":
            guard let sessionID, let id = data["inboxID"] as? String else { return nil }
            return .inboxDelivered(sessionID: sessionID, id: id)
        case "session.inbox.cancelled":
            guard let sessionID, let id = data["inboxID"] as? String else { return nil }
            return .inboxRemoved(sessionID: sessionID, id: id)
        case "session.inbox.delivery.changed":
            guard let sessionID, let id = data["inboxID"] as? String, let delivery = data["delivery"] as? String else { return nil }
            return .inboxDeliveryChanged(sessionID: sessionID, id: id, delivery: delivery)
        case "session.created":
            // v2.2.5 puts the session's fields in `data` itself; older payloads nest them in `info`.
            let info = data["info"] as? [String: Any] ?? data
            guard let id = info["id"] as? String ?? sessionID else { return nil }
            return .sessionCreated(
                id: id,
                parentID: info["parentID"] as? String ?? data["parentID"] as? String,
                title: info["title"] as? String,
                agent: info["agent"] as? String
            )
        case "shell.created":
            guard let info = data["info"] as? [String: Any],
                  let id = info["id"] as? String,
                  let shellSession = (info["metadata"] as? [String: Any])?["sessionID"] as? String
            else { return nil }
            return .shellCreated(id: id, sessionID: shellSession, at: created, command: info["command"] as? String)
        case "shell.exited":
            return (data["id"] as? String).map { .shellExited(id: $0) }
        case "session.tool.success":
            guard let sessionID else { return nil }
            guard let todos = (data["metadata"] as? [String: Any])?["todos"] as? [Any] else { return .toolFinished(sessionID: sessionID) }
            let items = todos.compactMap { item -> TodoItemV2? in
                guard let item = item as? [String: Any], let content = item["content"] as? String else { return nil }
                return TodoItemV2(content: content, status: item["status"] as? String ?? "pending")
            }
            return .todosWritten(sessionID: sessionID, todos: items)
        case "session.tool.failed":
            return sessionID.map { .toolFinished(sessionID: $0) }
        case "rpc.opencode.goal.changed":
            guard let sessionID else { return nil }
            // `goal: null` means cleared.
            guard let goal = data["goal"] as? [String: Any] else { return .goalChanged(sessionID: sessionID, goal: nil) }
            guard let state: GoalStateV2 = decoded(goal) else { return nil }
            return .goalChanged(sessionID: sessionID, goal: TodayGoal(state))
        default:
            return nil
        }
    }

    /// A kit DTO from a payload already parsed.
    private static func decoded<T: Decodable>(_ object: [String: Any]) -> T? {
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}

/// What the approval card says: the command or other resources, else the message, else the action.
struct PendingPermissionText {
    var action: String
    var resources: [String]
    var message: String?

    var text: String {
        let resources = resources.filter { !$0.isEmpty }
        if !resources.isEmpty { return resources.joined(separator: ", ") }
        if let message, !message.isEmpty { return message }
        return action
    }
}

/// What the question card says: the first field's question, else its header, else the form's title.
struct PendingQuestionText {
    struct Field {
        var title: String?
        var description: String?
    }

    var title: String?
    var fields: [Field]

    var text: String {
        let first = fields.first
        for candidate in [first?.description, first?.title, title] {
            if let candidate, !candidate.isEmpty { return candidate }
        }
        return ""
    }
}

/// A message in today's session's inbox, not delivered yet.
struct InboxEntry: Equatable, Sendable, Identifiable {
    var id: String
    /// `queue` (after this turn) or `steer`.
    var delivery: String
    var text: String = ""
    /// Its files, by name and type.
    var files: [InboxFileV2] = []
    /// `user`, or `synthetic` for a note the server made (a subagent's note that it finished).
    var kind: String = "user"
    /// `goal` for a goal's kickoff and each round's continuation (`payload.metadata.source`).
    var source: String?
    /// The goal a kickoff starts (`payload.metadata.goalID`).
    var goalID: String?

    /// A message someone sent: not a note the server made, and not a goal's kickoff or
    /// continuation, even one sent as a `user` item (a kickoff with images).
    var isMessage: Bool { kind != "synthetic" && source != "goal" }

    /// Uploaded files named in its `metadata.attachments`.
    var attachments: [PromptAttachment] = []

    /// How many of its files are images.
    var imageCount: Int { files.filter { $0.mime?.hasPrefix("image/") ?? true }.count }
    /// The text as shown: without its trailing path lines.
    var shownText: String { AttachedFiles.strip(text).text }
    /// Its uploaded files' names, from `metadata.attachments` or the path lines.
    var attachedFileNames: [String] { AttachedFiles.names(text: text, attachments: attachments) }
}

/// The state read back on every (re)connect.
struct LiveSnapshot: Equatable, Sendable {
    /// Nil when today's session isn't running.
    var busySince: Date?
    /// Whether the session ever ran (`time.busy` is set).
    var hasRun: Bool
    var children: [String]
    /// The session's `time.updated` (epoch ms), to tell whether anything is new since the card.
    var updated: Int64?
    /// The subagents running now.
    var runningChildren: [String] = []
    var pending: [PendingItem]
    /// Messages not delivered yet, in order.
    var inbox: [InboxEntry]
    /// The agent's to-do list ([] for none); nil when it couldn't be read, so the last one stays.
    var todos: [TodoItem]? = []
    /// Each subagent's title and agent, by id.
    var childSessions: [String: ChildSession] = [:]
    /// When each running subagent's run started (`time.busy`).
    var childBusySince: [String: Date] = [:]
    /// Today's session's project: `global` for a folder that isn't a git repository with commits.
    var projectID: String? = nil
}

/// Today's session as the stream tells it: busy since when, the subagents and agent shells it owns,
/// what it waits for, and the inbox. The server can't filter the stream by session, so events for
/// other sessions are dropped here: an event counts when its session is today's or one of its
/// subagents (a session whose parent is today's); `shell.exited` counts when its shell came from a
/// counted `shell.created`.
struct LiveSessionState: Equatable, Sendable {
    /// What an event asks of the summary.
    enum Effect: Equatable, Sendable {
        /// A run of today's session ended: refresh the summary.
        case runEnded
        /// A message reached today's session's agent, with its text when the inbox had it and the
        /// names of what went with it.
        case delivered(id: String, text: String?, attachments: [String] = [])
    }

    let rootID: String
    var busySince: Date?
    var hasRun = false
    var children: Set<String> = []
    /// Each subagent's title and agent, when known.
    var childSessions: [String: ChildSession] = [:]
    /// Subagents between `session.execution.started` and its end.
    var runningChildren: Set<String> = []
    /// When each running subagent's run started.
    var childBusySince: [String: Date] = [:]
    /// Agent shells running, and when each started.
    var shells: [String: Date] = [:]
    /// What each agent shell runs, when known.
    var shellEntries: [String: ShellEntry] = [:]
    /// Today's session's project, once read.
    var projectID: String?
    /// The agent's to-do list, cancelled items left out; empty for none.
    var todos: [TodoItem] = []
    /// Oldest first.
    var pending: [PendingItem] = []
    /// Messages not delivered yet, in arrival order.
    var inbox: [InboxEntry] = []

    init(rootID: String, hasRun: Bool = false) {
        self.rootID = rootID
        self.hasRun = hasRun
    }


    /// Messages that send after this turn.
    var waitingCount: Int { waiting.count }

    /// Messages that send after this turn, in order.
    var waiting: [InboxEntry] { inbox.filter { $0.delivery == "queue" && $0.isMessage } }

    /// Every message not delivered yet, after this turn or at the next step, in order (Details'
    /// Waiting to send). The server's own notes are left out.
    var undelivered: [InboxEntry] { inbox.filter(\.isMessage) }

    /// The question or approval the hint card shows: the oldest.
    var firstPending: PendingItem? { pending.first }

    /// Subagents and shell commands running, as Running now counts them: a shell counts once it
    /// has run for `shellDelay` (a quick command never shows).
    func runningCount(at date: Date) -> Int {
        runningChildren.count + shells.values.filter { date.timeIntervalSince($0) >= Self.shellDelay }.count
    }

    /// When the next young shell starts to count; nil when none is waiting.
    func nextShellCount(after date: Date) -> Date? {
        shells.values.map { $0.addingTimeInterval(Self.shellDelay) }.filter { $0 > date }.min()
    }

    static let shellDelay: TimeInterval = 10

    func owns(_ sessionID: String) -> Bool {
        sessionID == rootID || children.contains(sessionID)
    }

    /// Takes the state read after a (re)connect. Agent shells seen on the stream are kept.
    mutating func apply(_ snapshot: LiveSnapshot) {
        busySince = snapshot.busySince
        hasRun = hasRun || snapshot.hasRun
        children = Set(snapshot.children)
        childSessions.merge(snapshot.childSessions) { _, new in new }
        runningChildren = Set(snapshot.runningChildren)
        childBusySince = snapshot.childBusySince.filter { runningChildren.contains($0.key) }
        if let projectID = snapshot.projectID { self.projectID = projectID }
        pending = snapshot.pending
        if let todos = snapshot.todos { self.todos = todos }
        inbox = snapshot.inbox
    }

    /// Applies one event; events for other sessions change nothing.
    @discardableResult
    mutating func apply(_ event: SessionEvent) -> Effect? {
        switch event {
        case .connected:
            return nil
        case let .executionStarted(sessionID, at):
            if children.contains(sessionID) {
                runningChildren.insert(sessionID)
                if childBusySince[sessionID] == nil { childBusySince[sessionID] = at ?? Date() }
            }
            guard sessionID == rootID else { return nil }
            // One run includes the queued messages delivered during it: keep the first start.
            if busySince == nil { busySince = at ?? Date() }
            hasRun = true
            return nil
        case let .executionEnded(sessionID):
            runningChildren.remove(sessionID)
            childBusySince[sessionID] = nil
            // Pending ends with the run: Stop removes a pending approval without a
            // `permission.replied`, so a run's end drops what that session waited for.
            pending.removeAll { $0.sessionID == sessionID }
            guard sessionID == rootID else { return nil }
            busySince = nil
            hasRun = true
            return .runEnded
        case let .permissionAsked(item), let .formCreated(item):
            guard owns(item.sessionID), !pending.contains(where: { $0.id == item.id }) else { return nil }
            pending.append(item)
            return nil
        case let .permissionReplied(sessionID, id), let .formClosed(sessionID, id):
            guard owns(sessionID) else { return nil }
            pending.removeAll { $0.id == id }
            return nil
        case let .inboxEnqueued(sessionID, id, delivery, text, files, kind, metadata):
            guard sessionID == rootID, !inbox.contains(where: { $0.id == id }) else { return nil }
            inbox.append(InboxEntry(
                id: id,
                delivery: delivery,
                text: text,
                files: files,
                kind: kind,
                source: metadata?.source,
                goalID: metadata?.goalID,
                attachments: metadata?.attachments ?? []
            ))
            return nil
        case let .inboxDelivered(sessionID, id):
            guard sessionID == rootID else { return nil }
            let entry = inbox.first { $0.id == id }
            inbox.removeAll { $0.id == id }
            // The server's own items (a subagent's note, a goal's kickoff or continuation) never
            // set the You line.
            if let entry, !entry.isMessage { return nil }
            return .delivered(
                id: id,
                text: entry.flatMap { $0.text.isEmpty ? nil : $0.text },
                attachments: entry.map { $0.files.compactMap(\.name) + $0.attachedFileNames } ?? []
            )
        case let .inboxRemoved(sessionID, id):
            guard sessionID == rootID else { return nil }
            inbox.removeAll { $0.id == id }
            return nil
        case let .inboxDeliveryChanged(sessionID, id, delivery):
            guard sessionID == rootID, let index = inbox.firstIndex(where: { $0.id == id }) else { return nil }
            inbox[index].delivery = delivery
            return nil
        case let .sessionCreated(id, parentID, title, agent):
            guard parentID == rootID else { return nil }
            children.insert(id)
            childSessions[id] = ChildSession(title: title, agent: agent)
            return nil
        case let .shellCreated(id, sessionID, at, command):
            guard owns(sessionID) else { return nil }
            let started = at ?? Date()
            shells[id] = started
            shellEntries[id] = ShellEntry(sessionID: sessionID, command: command ?? "", startedAt: started)
            return nil
        case let .shellExited(id):
            shells[id] = nil
            shellEntries[id] = nil
            return nil
        case let .todosWritten(sessionID, todos):
            // Today's session's own list; a subagent's list is its own.
            guard sessionID == rootID else { return nil }
            self.todos = TodoList.items(todos)
            return nil
        case .toolFinished, .goalChanged:
            return nil
        }
    }

    /// Takes Running now as read when Details opens: the subagents running now (with their run's
    /// start) and the agent shells running in the project.
    mutating func apply(running: RunningSnapshot) {
        for (id, child) in running.childSessions {
            children.insert(id)
            childSessions[id] = child
        }
        runningChildren = Set(running.childBusySince.keys)
        childBusySince = running.childBusySince
        let owned = running.shells.filter { owns($0.sessionID) }
        shellEntries = Dictionary(owned.map { ($0.id, ShellEntry(sessionID: $0.sessionID, command: $0.command, startedAt: $0.startedAt)) }) { first, _ in first }
        shells = shellEntries.mapValues(\.startedAt)
    }

    /// An approval or question answered from this phone: it leaves at once. A refusal also refuses
    /// the same session's other approvals (the server does), so they go too.
    mutating func answered(_ item: PendingItem, refusedOthers: Bool = false) {
        pending.removeAll { $0.id == item.id || (refusedOthers && $0.kind == .approval && $0.sessionID == item.sessionID) }
    }
}

/// Running now as read from the server: running subagents and agent shells.
struct RunningSnapshot: Equatable, Sendable {
    struct Shell: Equatable, Sendable {
        var id: String
        var sessionID: String
        var command: String
        var startedAt: Date
    }

    /// Running subagents, and when each run started.
    var childBusySince: [String: Date]
    /// Their titles and agents.
    var childSessions: [String: ChildSession]
    /// Shells running in the project, each with the session that started it.
    var shells: [Shell]
}
