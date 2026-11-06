import Foundation
import OpenCodeKit

/// A question or approval the agent waits for, in today's session or one of its subagents.
struct PendingItem: Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case question
        case approval
    }

    var id: String
    var sessionID: String
    var kind: Kind
    /// The question's text, or the command or action to approve.
    var text: String
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
    /// A message joined the inbox; its files by name and type only, never their contents.
    case inboxEnqueued(sessionID: String, id: String, delivery: String, text: String, files: [InboxFileV2] = [])
    /// `session.inbox.delivered`: the message reached the agent.
    case inboxDelivered(sessionID: String, id: String)
    /// `session.inbox.cancelled`.
    case inboxRemoved(sessionID: String, id: String)
    case inboxDeliveryChanged(sessionID: String, id: String, delivery: String)
    case sessionCreated(id: String, parentID: String?)
    case shellCreated(id: String, sessionID: String, at: Date?)
    case shellExited(id: String)
    /// `session.tool.success` whose `metadata.todos` is a list: a `todowrite` call (the event
    /// doesn't name its tool).
    case todosWritten(sessionID: String, todos: [TodoItemV2])

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
        "session.tool.success",
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
            guard let sessionID, let id = data["id"] as? String else { return nil }
            let permission = PendingPermissionText(
                action: data["action"] as? String ?? "",
                resources: data["resources"] as? [String] ?? [],
                message: data["message"] as? String
            )
            return .permissionAsked(PendingItem(id: id, sessionID: sessionID, kind: .approval, text: permission.text))
        case "permission.replied":
            guard let sessionID, let id = data["requestID"] as? String else { return nil }
            return .permissionReplied(sessionID: sessionID, id: id)
        case "form.created":
            guard let form = data["form"] as? [String: Any],
                  let id = form["id"] as? String,
                  let formSession = form["sessionID"] as? String
            else { return nil }
            let fields = (form["fields"] as? [[String: Any]] ?? []).map {
                PendingQuestionText.Field(title: $0["title"] as? String, description: $0["description"] as? String)
            }
            let text = PendingQuestionText(title: form["title"] as? String, fields: fields).text
            return .formCreated(PendingItem(id: id, sessionID: formSession, kind: .question, text: text))
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
            return .inboxEnqueued(sessionID: sessionID, id: id, delivery: item?["delivery"] as? String ?? "queue", text: text, files: files)
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
            let info = data["info"] as? [String: Any]
            guard let id = info?["id"] as? String ?? sessionID else { return nil }
            return .sessionCreated(id: id, parentID: info?["parentID"] as? String)
        case "shell.created":
            guard let info = data["info"] as? [String: Any],
                  let id = info["id"] as? String,
                  let shellSession = (info["metadata"] as? [String: Any])?["sessionID"] as? String
            else { return nil }
            return .shellCreated(id: id, sessionID: shellSession, at: created)
        case "shell.exited":
            return (data["id"] as? String).map { .shellExited(id: $0) }
        case "session.tool.success":
            guard let sessionID, let todos = (data["metadata"] as? [String: Any])?["todos"] as? [Any] else { return nil }
            let items = todos.compactMap { item -> TodoItemV2? in
                guard let item = item as? [String: Any], let content = item["content"] as? String else { return nil }
                return TodoItemV2(content: content, status: item["status"] as? String ?? "pending")
            }
            return .todosWritten(sessionID: sessionID, todos: items)
        default:
            return nil
        }
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

    /// How many of its files are images.
    var imageCount: Int { files.filter { $0.mime?.hasPrefix("image/") ?? true }.count }
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
        /// A message reached today's session's agent, with its text when the inbox had it.
        case delivered(id: String, text: String?)
    }

    let rootID: String
    var busySince: Date?
    var hasRun = false
    var children: Set<String> = []
    /// Subagents between `session.execution.started` and its end.
    var runningChildren: Set<String> = []
    /// Agent shells running, and when each started.
    var shells: [String: Date] = [:]
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
    var waiting: [InboxEntry] { inbox.filter { $0.delivery == "queue" } }

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
        runningChildren = Set(snapshot.runningChildren)
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
            if children.contains(sessionID) { runningChildren.insert(sessionID) }
            guard sessionID == rootID else { return nil }
            // One run includes the queued messages delivered during it: keep the first start.
            if busySince == nil { busySince = at ?? Date() }
            hasRun = true
            return nil
        case let .executionEnded(sessionID):
            runningChildren.remove(sessionID)
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
        case let .inboxEnqueued(sessionID, id, delivery, text, files):
            guard sessionID == rootID, !inbox.contains(where: { $0.id == id }) else { return nil }
            inbox.append(InboxEntry(id: id, delivery: delivery, text: text, files: files))
            return nil
        case let .inboxDelivered(sessionID, id):
            guard sessionID == rootID else { return nil }
            let text = inbox.first { $0.id == id }?.text
            inbox.removeAll { $0.id == id }
            return .delivered(id: id, text: text.flatMap { $0.isEmpty ? nil : $0 })
        case let .inboxRemoved(sessionID, id):
            guard sessionID == rootID else { return nil }
            inbox.removeAll { $0.id == id }
            return nil
        case let .inboxDeliveryChanged(sessionID, id, delivery):
            guard sessionID == rootID, let index = inbox.firstIndex(where: { $0.id == id }) else { return nil }
            inbox[index].delivery = delivery
            return nil
        case let .sessionCreated(id, parentID):
            if parentID == rootID { children.insert(id) }
            return nil
        case let .shellCreated(id, sessionID, at):
            if owns(sessionID) { shells[id] = at ?? Date() }
            return nil
        case let .shellExited(id):
            shells[id] = nil
            return nil
        case let .todosWritten(sessionID, todos):
            // Today's session's own list; a subagent's list is its own.
            guard sessionID == rootID else { return nil }
            self.todos = TodoList.items(todos)
            return nil
        }
    }
}
