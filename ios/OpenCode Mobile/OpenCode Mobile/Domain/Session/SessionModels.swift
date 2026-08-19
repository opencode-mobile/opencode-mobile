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

    var isArchived: Bool { archivedAtEpochMs != nil }
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

struct VcsInfo: Sendable, Equatable {
    let currentBranch: String?
    let defaultBranch: String?

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
    case deny
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

    init(
        question: String,
        header: String,
        options: [QuestionOption],
        multiple: Bool,
        custom: Bool,
        fieldKey: String? = nil
    ) {
        self.question = question
        self.header = header
        self.options = options
        self.multiple = multiple
        self.custom = custom
        self.fieldKey = fieldKey
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
