import Foundation

struct MessageDTO: Codable, Sendable {
    let info: MessageInfoDTO
    let parts: [MessagePartDTO]
}

struct MessageInfoDTO: Codable, Sendable {
    let id: String
    let sessionID: String?
    let role: String
    let agent: String?
    let mode: String?
    let model: MessageModelRefDTO?
    let modelID: String?
    let providerID: String?
    let time: MessageTimeDTO?
    let error: MessageErrorInfoDTO?
}

struct MessageErrorInfoDTO: Codable, Sendable {
    let name: String?
    let data: MessageErrorDataDTO?
}

struct MessageErrorDataDTO: Codable, Sendable {
    let message: String?
    let statusCode: Int?
    let responseBody: String?
}

struct MessageModelRefDTO: Codable, Sendable {
    let modelID: String?
    let providerID: String?
}

struct MessageTimeDTO: Codable, Sendable {
    let created: Int64?
    let completed: Int64?
}

struct MessagePartDTO: Codable, Sendable {
    let type: String
    let text: String?
    let tool: String?
}

struct SendMessageRequestDTO: Codable, Sendable {
    let parts: [SendMessagePartDTO]
}

struct SendMessagePartDTO: Codable, Sendable {
    let type: String
    let text: String
}
