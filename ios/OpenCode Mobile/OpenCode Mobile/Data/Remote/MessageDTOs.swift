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
    let variant: String?
    let time: MessageTimeDTO?
    let error: MessageErrorInfoDTO?
    let tokens: MessageTokensDTO?
    let cost: Double?
}

struct MessageTokensDTO: Codable, Sendable {
    let input: Int64?
    let output: Int64?
    let reasoning: Int64?
    let cache: MessageTokenCacheDTO?
}

struct MessageTokenCacheDTO: Codable, Sendable {
    let read: Int64?
    let write: Int64?
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
    let id: String?
    let sessionID: String?
    let messageID: String?
    let type: String
    let text: String?
    let system: String?
    let tool: String?
    let source: [String: AnyCodable]?
    let callID: String?
    let state: MessageToolStateDTO?
    let snapshot: String?
    let reason: String?
    let name: String?
    let prompt: String?
    let description: String?
    let agent: String?
    let attempt: Int?
    let error: MessageErrorInfoDTO?
    let time: MessagePartTimeDTO?
    let synthetic: Bool?
    let ignored: Bool?
    let hash: String?
    let files: [String]?
    let auto: Bool?
    let subtask: String?
}

struct MessageToolStateDTO: Codable, Sendable {
    let status: String
    let input: [String: AnyCodable]?
    let raw: String?
    let title: String?
    let output: String?
    let error: String?
    let time: MessageToolTimeDTO?
    let metadata: [String: AnyCodable]?
}

struct MessageToolTimeDTO: Codable, Sendable {
    let start: Int64?
    let end: Int64?
    let compacted: Int64?
    let created: Int64?
}

struct MessagePartTimeDTO: Codable, Sendable {
    let start: Int64?
    let end: Int64?
}

struct SendMessageRequestDTO: Codable, Sendable {
    let parts: [SendMessagePartDTO]
    let model: SendPromptModelDTO?
    let variant: String?

    init(parts: [SendMessagePartDTO], model: SendPromptModelDTO? = nil, variant: String? = nil) {
        self.parts = parts
        self.model = model
        self.variant = variant
    }
}

struct SendPromptModelDTO: Codable, Sendable {
    let providerID: String
    let modelID: String
}

struct SendMessagePartDTO: Codable, Sendable {
    let type: String
    let text: String
}

/// A type-erased Codable value for flexible JSON content.
enum AnyCodable: Codable, Sendable, Equatable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case dictionary([String: AnyCodable])
    case array([AnyCodable])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let v = try? container.decode(String.self) { self = .string(v) }
        else if let v = try? container.decode(Int.self) { self = .int(v) }
        else if let v = try? container.decode(Double.self) { self = .double(v) }
        else if let v = try? container.decode(Bool.self) { self = .bool(v) }
        else if let v = try? container.decode([String: AnyCodable].self) { self = .dictionary(v) }
        else if let v = try? container.decode([AnyCodable].self) { self = .array(v) }
        else { throw DecodingError.dataCorruptedError(in: container, debugDescription: "AnyCodable: unexpected value") }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let v): try container.encode(v)
        case .int(let v): try container.encode(v)
        case .double(let v): try container.encode(v)
        case .bool(let v): try container.encode(v)
        case .dictionary(let v): try container.encode(v)
        case .array(let v): try container.encode(v)
        case .null: try container.encodeNil()
        }
    }
}

extension AnyCodable {
    var stringValue: String? {
        if case let .string(s) = self { s } else { nil }
    }
}
