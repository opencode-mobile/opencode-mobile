import Foundation

public struct ConfigProvidersResponseDTO: Codable, Sendable {
    public let providers: [ProviderDTO]
    public let `default`: [String: String]

    public init(
        providers: [ProviderDTO],
        `default`: [String: String]
    ) {
        self.providers = providers
        self.default = `default`
    }
}

public struct ProviderDTO: Codable, Sendable {
    public let id: String
    public let name: String?
    public let models: [String: ProviderModelDTO]

    public init(
        id: String,
        name: String?,
        models: [String: ProviderModelDTO]
    ) {
        self.id = id
        self.name = name
        self.models = models
    }
}

public struct ProviderModelDTO: Codable, Sendable {
    public let id: String?
    public let name: String?
    public let status: String?
    public let disabled: Bool?
    public let variants: [String: JSONValueDTO]?
    public let limit: ProviderModelLimitDTO?
    public let capabilities: ProviderModelCapabilitiesDTO?

    public init(
        id: String?,
        name: String?,
        status: String?,
        disabled: Bool?,
        variants: [String: JSONValueDTO]?,
        limit: ProviderModelLimitDTO?,
        capabilities: ProviderModelCapabilitiesDTO?
    ) {
        self.id = id
        self.name = name
        self.status = status
        self.disabled = disabled
        self.variants = variants
        self.limit = limit
        self.capabilities = capabilities
    }
}

public struct ProviderModelCapabilitiesDTO: Codable, Sendable {
    public let attachment: Bool?
    public let input: ProviderModelInputCapabilitiesDTO?

    public init(
        attachment: Bool?,
        input: ProviderModelInputCapabilitiesDTO?
    ) {
        self.attachment = attachment
        self.input = input
    }
}

public struct ProviderModelInputCapabilitiesDTO: Codable, Sendable {
    public let text: Bool?
    public let audio: Bool?
    public let image: Bool?
    public let video: Bool?
    public let pdf: Bool?

    public init(
        text: Bool?,
        audio: Bool?,
        image: Bool?,
        video: Bool?,
        pdf: Bool?
    ) {
        self.text = text
        self.audio = audio
        self.image = image
        self.video = video
        self.pdf = pdf
    }
}

public struct ProviderModelLimitDTO: Codable, Sendable {
    public let context: Int64?
    public let output: Int64?

    public init(
        context: Int64?,
        output: Int64?
    ) {
        self.context = context
        self.output = output
    }
}

public enum JSONValueDTO: Codable, Sendable, Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSONValueDTO])
    case array([JSONValueDTO])
    case null

    public init(from decoder: Decoder) throws {
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

    public func encode(to encoder: Encoder) throws {
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
