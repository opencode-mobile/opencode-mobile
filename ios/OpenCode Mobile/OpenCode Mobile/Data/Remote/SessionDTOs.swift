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
    let model: SessionModelDTO?
    let time: SessionTimeDTO?
}

struct SessionModelDTO: Codable, Sendable {
    let id: String?
    let providerID: String?
    let variant: String?
}

struct SessionTimeDTO: Codable, Sendable {
    let created: Int64?
    let updated: Int64?
}

struct CreateSessionRequestDTO: Codable, Sendable {
    let parentID: String?
    let title: String?
}

struct UpdateSessionRequestDTO: Codable, Sendable {
    let title: String?
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

struct TodoDTO: Codable, Sendable {}

struct FileDiffDTO: Decodable, Sendable {
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
