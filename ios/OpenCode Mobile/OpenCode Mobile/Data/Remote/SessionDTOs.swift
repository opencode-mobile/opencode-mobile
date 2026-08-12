import Foundation

struct SessionDTO: Codable, Sendable {
    let id: String
    let title: String
    let slug: String?
    let projectID: String?
    let directory: String?
    let parentID: String?
    let version: String?
    let cost: Double?
    let tokens: MessageTokensDTO?
    let time: SessionTimeDTO?
    let revert: SessionRevertDTO?
}

struct SessionRevertDTO: Codable, Sendable {
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

struct SessionTimeDTO: Codable, Sendable {
    let created: Int64?
    let updated: Int64?
    let archived: Int64?

    init(created: Int64? = nil, updated: Int64? = nil, archived: Int64? = nil) {
        self.created = created
        self.updated = updated
        self.archived = archived
    }
}

struct CreateSessionRequestDTO: Codable, Sendable {
    let parentID: String?
    let title: String?
}

struct UpdateSessionTimeDTO: Codable, Sendable {
    let archived: Int64?
}

struct UpdateSessionRequestDTO: Codable, Sendable {
    let title: String?
    let time: UpdateSessionTimeDTO?

    init(title: String? = nil, time: UpdateSessionTimeDTO? = nil) {
        self.title = title
        self.time = time
    }
}

struct ForkSessionRequestDTO: Codable, Sendable {
    let messageID: String?
}

struct InitSessionRequestDTO: Codable, Sendable {
    let messageID: String
    let providerID: String
    let modelID: String
}

struct SummarizeSessionRequestDTO: Codable, Sendable {
    let providerID: String
    let modelID: String
}

struct RevertSessionRequestDTO: Codable, Sendable {
    let messageID: String
    let partID: String?
}

struct PermissionResponseRequestDTO: Codable, Sendable {
    let response: String
}

struct PermissionRequestDTO: Codable, Sendable {
    let id: String
    let sessionID: String
    let permission: String
    let patterns: [String]
    let metadata: [String: AnyCodable]
    let always: [String]
    let tool: PermissionToolDTO?
}

struct PermissionToolDTO: Codable, Sendable {
    let messageID: String
    let callID: String
}

nonisolated struct QuestionReplyRequestDTO: Codable, Sendable {
    let answers: [[String]]
}

nonisolated struct QuestionRequestDTO: Codable, Sendable {
    let id: String
    let sessionID: String
    let questions: [QuestionInfoDTO]
    let tool: QuestionToolDTO?
}

nonisolated struct QuestionInfoDTO: Codable, Sendable {
    let question: String
    let header: String
    let options: [QuestionOptionDTO]
    let multiple: Bool?
    let custom: Bool?
}

nonisolated struct QuestionOptionDTO: Codable, Sendable {
    let label: String
    let description: String?
}

nonisolated struct QuestionToolDTO: Codable, Sendable {
    let messageID: String
    let callID: String
}

struct TodoDTO: Codable, Sendable {
    let content: String
    let status: String
    let priority: String
}

nonisolated struct FileDiffDTO: Decodable, Sendable {
    let file: String
    let patch: String?
    let before: String?
    let after: String?
    let additions: Int
    let deletions: Int
    let status: String?

    private enum CodingKeys: String, CodingKey {
        case file
        case path
        case patch
        case before
        case after
        case additions
        case deletions
        case status
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        file = try container.decodeIfPresent(String.self, forKey: .file)
            ?? container.decode(String.self, forKey: .path)
        patch = try container.decodeIfPresent(String.self, forKey: .patch)
        before = try container.decodeIfPresent(String.self, forKey: .before)
        after = try container.decodeIfPresent(String.self, forKey: .after)
        additions = try container.decodeIfPresent(Int.self, forKey: .additions) ?? 0
        deletions = try container.decodeIfPresent(Int.self, forKey: .deletions) ?? 0
        status = try container.decodeIfPresent(String.self, forKey: .status)
    }
}

struct VcsInfoDTO: Decodable, Sendable {
    let branch: String?
    let defaultBranch: String?

    private enum CodingKeys: String, CodingKey {
        case branch
        case defaultBranch = "default_branch"
    }
}
