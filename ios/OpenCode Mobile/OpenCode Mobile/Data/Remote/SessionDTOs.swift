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

struct FileDiffDTO: Codable, Sendable {}
