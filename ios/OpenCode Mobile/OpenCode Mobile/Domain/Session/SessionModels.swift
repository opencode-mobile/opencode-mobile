import Foundation

struct Session: Sendable, Equatable {
    let id: String
    var title: String
    let slug: String?
    let projectID: String?
    let directory: String?
    let parentID: String?
    var cost: Double? = nil
    var tokens: MessageTokenUsage? = nil
    var createdAtEpochMs: Int64? = nil
    let updatedAtEpochMs: Int64?
    var archivedAtEpochMs: Int64? = nil
    var revert: SessionRevert? = nil
    /// v2 only: when the latest run ended (strictly increasing per session).
    var idleAtEpochMs: Int64? = nil
    /// v2 only: server-wide "viewed up to" watermark for `idleAtEpochMs`.
    var viewedAtEpochMs: Int64? = nil
    /// v2 only: how the latest run ended.
    var idleOutcome: SessionIdleOutcome? = nil

    var isArchived: Bool { archivedAtEpochMs != nil }

    /// A finished run nobody has viewed yet (v2 server state; always false on v1).
    var isServerUnread: Bool {
        guard let idleAtEpochMs else { return false }
        guard let viewedAtEpochMs else { return true }
        return idleAtEpochMs > viewedAtEpochMs
    }

    /// Applies a `session.viewed` watermark; `viewedAtEpochMs` only moves forward.
    func withViewed(idleAtEpochMs idle: Int64) -> Session {
        var copy = self
        copy.viewedAtEpochMs = max(viewedAtEpochMs ?? idle, idle)
        return copy
    }
}

/// How the latest run of a session ended, as reported by the v2 server.
enum SessionIdleOutcome: String, Sendable, Equatable {
    case succeeded
    case failed
    case interrupted

    init?(wireValue: String?) {
        guard let wireValue else { return nil }
        self.init(rawValue: wireValue.lowercased())
    }
}

struct SessionRevert: Sendable, Equatable {
    let messageID: String
    let partID: String?
    let snapshot: String?
    let diff: String?

    init(messageID: String, partID: String? = nil, snapshot: String? = nil, diff: String? = nil) {
        self.messageID = messageID
        self.partID = partID
        self.snapshot = snapshot
        self.diff = diff
    }
}

extension Array where Element == Session {
    func activeSessions() -> [Session] {
        filter { !$0.isArchived }
    }

    func rootSessions() -> [Session] {
        filter { $0.parentID == nil }
    }
}

struct SessionStatus: Sendable, Equatable {
    let sessionID: String
    let raw: [String: String]
}

enum SessionActivity: Sendable, Equatable {
    case idle
    case running
    case retrying(
        attempt: Int?,
        nextAtMs: Int64?,
        message: String?,
        errorType: String?,
        actionLabel: String?,
        actionLink: String?
    )

    var isBusy: Bool {
        switch self {
        case .running, .retrying: true
        case .idle: false
        }
    }

    var statusLabel: String? {
        switch self {
        case .idle:
            nil
        case .running:
            "Working"
        case let .retrying(attempt, _, _, _, _, _):
            if let attempt {
                "Retrying · \(attempt)"
            } else {
                "Retrying"
            }
        }
    }

    /// In-chat label. The home list keeps `statusLabel` ("Retrying · N") because
    /// it parses the attempt off that exact prefix.
    var chatStatusLabel: String? {
        guard case let .retrying(_, _, message, _, _, _) = self else { return statusLabel }
        let base = statusLabel
        guard let reason = message?.trimmingCharacters(in: .whitespacesAndNewlines), !reason.isEmpty else {
            return base
        }
        return "\(base ?? "Retrying") · \(reason.prefix(80))"
    }

    /// Live countdown card. Content-filter retries stay a caption only.
    var retryCard: SessionRetryCard? {
        guard case let .retrying(attempt, nextAtMs, message, errorType, actionLabel, actionLink) = self else {
            return nil
        }
        if errorType?.caseInsensitiveCompare("provider.content-filter") == .orderedSame { return nil }
        guard let nextAtMs else { return nil }
        let trimmed = message?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let text = trimmed.isEmpty ? "Retrying" : String(trimmed.prefix(80))
        return SessionRetryCard(
            message: text,
            attempt: attempt,
            nextAtMs: nextAtMs,
            actionLabel: trimmedNonEmpty(actionLabel),
            actionLink: trimmedNonEmpty(actionLink)
        )
    }

    init(raw: [String: String]) {
        switch raw["type"]?.lowercased() {
        case "busy", "running":
            self = .running
        case "retry":
            self = .retrying(
                attempt: raw["attempt"].flatMap(Int.init),
                nextAtMs: raw["nextAtMs"].flatMap(Int64.init)
                    ?? raw["nextAt"].flatMap(Int64.init)
                    ?? raw["next"].flatMap(Int64.init),
                message: raw["message"],
                errorType: raw["errorType"],
                actionLabel: raw["actionLabel"],
                actionLink: raw["actionLink"]
            )
        default:
            self = .idle
        }
    }
}

/// Countdown shown while the server is waiting to retry a rate limit or quota error.
struct SessionRetryCard: Equatable, Sendable {
    let message: String
    let attempt: Int?
    let nextAtMs: Int64
    let actionLabel: String?
    let actionLink: String?
}

private func trimmedNonEmpty(_ value: String?) -> String? {
    let text = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return text.isEmpty ? nil : text
}

enum SessionActivitySource: Sendable, Equatable {
    case server
    case optimistic
    case cached
}

struct SessionActivityEntry: Sendable, Equatable {
    let activity: SessionActivity
    let source: SessionActivitySource
    let observedAtMs: Int64
}

struct SessionActivitySnapshot: Sendable, Equatable {
    var entries: [String: SessionActivityEntry]
    var seeded: Bool
    var fromCache: Bool

    static let empty = SessionActivitySnapshot(entries: [:], seeded: false, fromCache: false)

    init(entries: [String: SessionActivityEntry], seeded: Bool, fromCache: Bool = false) {
        self.entries = entries
        self.seeded = seeded
        self.fromCache = fromCache
    }

    func statusLabel(for sessionID: String) -> String? {
        guard let entry = entries[sessionID] else { return nil }
        guard seeded || entry.source == .cached else { return nil }
        return entry.activity.statusLabel
    }

    func chatStatusLabel(for sessionID: String) -> String? {
        guard let entry = entries[sessionID] else { return nil }
        guard seeded || entry.source == .cached else { return nil }
        return entry.activity.chatStatusLabel
    }

    func isBusy(for sessionID: String) -> Bool {
        guard let entry = entries[sessionID] else { return false }
        guard seeded || entry.source == .cached else { return false }
        return entry.activity.isBusy
    }

    func statusIsStale(for sessionID: String) -> Bool {
        entries[sessionID]?.source == .cached
    }

    /// Session whose activity Home should show for a root row.
    ///
    /// The opencode desktop home row is working when the session or any
    /// descendant is in the active set. A parent blocked on a child is working
    /// even when its own step is idle. Nil when nobody in the family is busy;
    /// the caller keeps the root's own idle path.
    func homeActivitySessionID(rootID: String, sessions: [Session]) -> String? {
        var bestID: String?
        var bestRank = 0
        var bestFresh = false
        for id in SessionFamily.memberIDs(of: rootID, in: sessions) {
            guard let entry = visibleEntry(id), entry.activity.isBusy else { continue }
            let rank = entry.activity.homeRank
            let fresh = entry.source != .cached
            let preferRoot = id == rootID && fresh == bestFresh
            if rank > bestRank || (rank == bestRank && fresh && !bestFresh) || (rank == bestRank && preferRoot) {
                bestRank = rank
                bestFresh = fresh
                bestID = id
            }
        }
        return bestID
    }

    private func visibleEntry(_ sessionID: String) -> SessionActivityEntry? {
        guard let entry = entries[sessionID] else { return nil }
        guard seeded || entry.source == .cached else { return nil }
        return entry
    }
}

enum SessionFamily {
    /// The root and every non-archived descendant whose parent chain resolves to it.
    static func memberIDs(of rootID: String, in sessions: [Session]) -> [String] {
        let parentByID = Dictionary(sessions.map { ($0.id, $0.parentID) }, uniquingKeysWith: { _, latest in latest })
        var members = [rootID]
        for session in sessions where session.id != rootID && !session.isArchived {
            if resolveRoot(session.id, parentByID: parentByID) == rootID {
                members.append(session.id)
            }
        }
        return members
    }

    /// Walk `parentID` to the furthest known ancestor. A missing parent stops the walk.
    static func resolveRoot(_ sessionID: String, parentByID: [String: String?]) -> String {
        var current = sessionID
        var seen: Set<String> = [sessionID]
        while let parent = parentByID[current] ?? nil {
            if parent.isEmpty || seen.contains(parent) { break }
            seen.insert(parent)
            current = parent
        }
        return current
    }
}

private extension SessionActivity {
    /// Running outranks a retry; both outrank idle.
    var homeRank: Int {
        switch self {
        case .running: 2
        case .retrying: 1
        case .idle: 0
        }
    }
}

struct SessionActivityCacheEntry: Sendable, Equatable {
    let sessionId: String
    let activity: SessionActivity
    let observedAtMs: Int64
}

enum SessionActivityPolicy {
    static let optimisticTtlMs: Int64 = 10_000
    static let abortOptimisticTtlMs: Int64 = 3_000
    static let runningReconcileMs = 30_000
    static let idleConfirmMs = 3_000
    static let pendingSendTimeoutMs: Int64 = 8_000
    static let cachedActivityTtlMs: Int64 = 6 * 60 * 60 * 1_000
    static let activityRememberDebounceMs: UInt64 = 1_000
}

enum SessionTodoStatus: Sendable, Equatable {
    case pending
    case inProgress
    case completed
    case cancelled

    init(parsing raw: String) {
        switch raw.lowercased() {
        case "in_progress", "in-progress", "inprogress":
            self = .inProgress
        case "completed", "complete", "done":
            self = .completed
        case "cancelled", "canceled":
            self = .cancelled
        default:
            self = .pending
        }
    }
}

struct SessionTodo: Sendable, Equatable, Identifiable {
    /// List order is identity; no server id.
    var id: Int { position }
    let position: Int
    let content: String
    let status: SessionTodoStatus
    let priority: String
}

enum SessionTodoList {
    static func completedCount(in todos: [SessionTodo]) -> Int {
        todos.filter { $0.status == .completed }.count
    }

    static func isFinished(_ todos: [SessionTodo]) -> Bool {
        !todos.isEmpty && todos.allSatisfy { $0.status == .completed || $0.status == .cancelled }
    }

    static func activePreview(in todos: [SessionTodo]) -> SessionTodo? {
        if let inProgress = todos.first(where: { $0.status == .inProgress }) { return inProgress }
        if let pending = todos.first(where: { $0.status == .pending }) { return pending }
        if let completed = todos.last(where: { $0.status == .completed }) { return completed }
        return todos.first
    }

    /// Visible while non-empty and not fully finished, including after idle.
    static func isVisible(_ todos: [SessionTodo]) -> Bool {
        !todos.isEmpty && !isFinished(todos)
    }

    /// Hold the all-done pill only after this presentation already showed an unfinished list.
    static func shouldHoldFinished(sawUnfinished: Bool, todos: [SessionTodo]) -> Bool {
        sawUnfinished && isFinished(todos)
    }
}

enum FileDiffStatus: String, Sendable, Equatable {
    case added
    case deleted
    case modified
}

struct FileDiff: Sendable, Equatable, Identifiable {
    var id: String { path }
    let path: String
    let patch: String?
    let additions: Int
    let deletions: Int
    let status: FileDiffStatus
}

/// One uncommitted working-copy change reported by the server VCS status endpoint.
struct VcsFileStatus: Sendable, Equatable, Identifiable {
    var id: String { path }
    let path: String
    let status: FileDiffStatus
    let additions: Int
    let deletions: Int
}

struct VcsInfo: Sendable, Equatable {
    let currentBranch: String?
    let defaultBranch: String?
    /// False while a v2 server has not detected the repository yet (no `provider`, empty
    /// `branch`): the branch is unknown rather than absent. v1 answers are always settled.
    var isSettled = true

    var supportsGit: Bool { currentBranch != nil || defaultBranch != nil }
    var hasDistinctMainBranch: Bool {
        guard let currentBranch, let defaultBranch else { return false }
        return currentBranch != defaultBranch
    }
}

enum VcsDiffMode: String, Sendable {
    case workingTree = "git"
    case mainBranch = "branch"

    var v2WireValue: String {
        switch self {
        case .workingTree: "working"
        case .mainBranch: "branch"
        }
    }
}

enum PermissionDecision: Sendable {
    case allowOnce
    /// Allow and save the request's `always` patterns (v1 `always`, v2 `decision: "always"`).
    case alwaysAllow
    case deny
}

/// One file an edit/write permission would change, from the request metadata.
struct PermissionFileChange: Sendable, Equatable {
    let path: String
    let additions: Int
    let deletions: Int
}

struct PermissionRequest: Sendable, Equatable, Identifiable {
    let id: String
    let sessionID: String
    let permission: String
    let patterns: [String]
    let metadata: [String: AnyCodable]
    let always: [String]
    let toolMessageID: String?
    let toolCallID: String?

    /// Files an edit/write would change; empty for other permissions.
    var fileChanges: [PermissionFileChange] { PermissionFileChanges.from(metadata) }
}

struct QuestionOption: Sendable, Equatable, Identifiable {
    let label: String
    let description: String
    let value: String

    var id: String { label }

    init(label: String, description: String, value: String? = nil) {
        self.label = label
        self.description = description
        self.value = value ?? label
    }
}

struct QuestionInfo: Sendable, Equatable {
    let question: String
    let header: String
    let options: [QuestionOption]
    let multiple: Bool
    let custom: Bool
    let fieldKey: String?
    /// Form multiselect bounds (`minItems` / `maxItems`); nil means unbounded.
    let minSelections: Int?
    let maxSelections: Int?

    init(
        question: String,
        header: String,
        options: [QuestionOption],
        multiple: Bool,
        custom: Bool,
        fieldKey: String? = nil,
        minSelections: Int? = nil,
        maxSelections: Int? = nil
    ) {
        self.question = question
        self.header = header
        self.options = options
        self.multiple = multiple
        self.custom = custom
        self.fieldKey = fieldKey
        self.minSelections = minSelections
        self.maxSelections = maxSelections
    }
}

struct QuestionRequest: Sendable, Equatable, Identifiable {
    let id: String
    let sessionID: String
    let questions: [QuestionInfo]
    let toolMessageID: String?
    let toolCallID: String?
}

enum SessionError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case decoding(String)
}

extension SessionError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case let .transport(message):
            return message
        case let .http(_, message):
            return message ?? "Request failed"
        case let .decoding(message):
            return message
        }
    }
}
