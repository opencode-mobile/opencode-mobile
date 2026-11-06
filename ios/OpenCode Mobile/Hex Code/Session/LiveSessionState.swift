import Foundation

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
    case inboxEnqueued(sessionID: String, id: String, delivery: String, text: String)
    /// `session.inbox.delivered` or `.cancelled`.
    case inboxRemoved(sessionID: String, id: String)
    case inboxDeliveryChanged(sessionID: String, id: String, delivery: String)
    case sessionCreated(id: String, parentID: String?)
    case shellCreated(id: String, sessionID: String)
    case shellExited(id: String)

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
            let text = (item?["payload"] as? [String: Any])?["text"] as? String ?? ""
            return .inboxEnqueued(sessionID: sessionID, id: id, delivery: item?["delivery"] as? String ?? "queue", text: text)
        case "session.inbox.delivered", "session.inbox.cancelled":
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
            return .shellCreated(id: id, sessionID: shellSession)
        case "shell.exited":
            return (data["id"] as? String).map { .shellExited(id: $0) }
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
}

/// The state read back on every (re)connect.
struct LiveSnapshot: Equatable, Sendable {
    /// Nil when today's session isn't running.
    var busySince: Date?
    /// Whether the session ever ran (`time.busy` is set).
    var hasRun: Bool
    var children: [String]
    var pending: [PendingItem]
    /// Messages not delivered yet, in order.
    var inbox: [InboxEntry]
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
    }

    let rootID: String
    var busySince: Date?
    var hasRun = false
    var children: Set<String> = []
    var shells: Set<String> = []
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

    func owns(_ sessionID: String) -> Bool {
        sessionID == rootID || children.contains(sessionID)
    }

    /// Takes the state read after a (re)connect. Agent shells seen on the stream are kept.
    mutating func apply(_ snapshot: LiveSnapshot) {
        busySince = snapshot.busySince
        hasRun = hasRun || snapshot.hasRun
        children = Set(snapshot.children)
        pending = snapshot.pending
        inbox = snapshot.inbox
    }

    /// Applies one event; events for other sessions change nothing.
    @discardableResult
    mutating func apply(_ event: SessionEvent) -> Effect? {
        switch event {
        case .connected:
            return nil
        case let .executionStarted(sessionID, at):
            guard sessionID == rootID else { return nil }
            // One run includes the queued messages delivered during it: keep the first start.
            if busySince == nil { busySince = at ?? Date() }
            hasRun = true
            return nil
        case let .executionEnded(sessionID):
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
        case let .inboxEnqueued(sessionID, id, delivery, text):
            guard sessionID == rootID, !inbox.contains(where: { $0.id == id }) else { return nil }
            inbox.append(InboxEntry(id: id, delivery: delivery, text: text))
            return nil
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
        case let .shellCreated(id, sessionID):
            if owns(sessionID) { shells.insert(id) }
            return nil
        case let .shellExited(id):
            shells.remove(id)
            return nil
        }
    }
}
