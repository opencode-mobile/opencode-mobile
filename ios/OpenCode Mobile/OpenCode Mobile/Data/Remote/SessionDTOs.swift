import Foundation

struct SessionDTO: Codable, Sendable {
    let id: String
    let title: String
    let slug: String?
    let projectID: String?
    let directory: String?
    let parentID: String?
    let version: String?
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

struct TodoDTO: Codable, Sendable {}

struct FileDiffDTO: Codable, Sendable {}
