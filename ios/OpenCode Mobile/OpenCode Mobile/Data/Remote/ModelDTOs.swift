import Foundation

struct ConfigProvidersResponseDTO: Codable, Sendable {
    let providers: [ProviderDTO]
    let `default`: [String: String]
}

struct ProviderDTO: Codable, Sendable {
    let id: String
    let name: String?
    let models: [String: ProviderModelDTO]
}

struct ProviderModelDTO: Codable, Sendable {
    let id: String?
    let name: String?
    let status: String?
    let disabled: Bool?
    let variants: [String: JSONValueDTO]?
    let limit: ProviderModelLimitDTO?
    let capabilities: ProviderModelCapabilitiesDTO?
}

struct ProviderModelCapabilitiesDTO: Codable, Sendable {
    let attachment: Bool?
    let input: ProviderModelInputCapabilitiesDTO?
}

struct ProviderModelInputCapabilitiesDTO: Codable, Sendable {
    let text: Bool?
    let audio: Bool?
    let image: Bool?
    let video: Bool?
    let pdf: Bool?
}

struct ProviderModelLimitDTO: Codable, Sendable {
    let context: Int64?
    let output: Int64?
}

enum JSONValueDTO: Codable, Sendable, Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSONValueDTO])
    case array([JSONValueDTO])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValueDTO].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: JSONValueDTO].self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case let .string(value):
            try container.encode(value)
        case let .number(value):
            try container.encode(value)
        case let .bool(value):
            try container.encode(value)
        case let .object(value):
            try container.encode(value)
        case let .array(value):
            try container.encode(value)
        case .null:
            try container.encodeNil()
        }
    }
}
