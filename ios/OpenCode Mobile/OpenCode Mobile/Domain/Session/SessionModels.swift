import Foundation

struct Session: Sendable, Equatable {
    let id: String
    let title: String
    let slug: String?
    let projectID: String?
    let directory: String?
    let parentID: String?
    var cost: Double? = nil
    var tokens: MessageTokenUsage? = nil
    var modelProviderID: String? = nil
    var modelID: String? = nil
    var variant: String? = nil
    var createdAtEpochMs: Int64? = nil
    let updatedAtEpochMs: Int64?
}

struct SessionStatus: Sendable, Equatable {
    let sessionID: String
    let raw: [String: String]
}

struct SessionTodo: Sendable, Equatable {
    let raw: String
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
