import Foundation

public nonisolated struct V2CommandDTO: Decodable, Sendable {
    public let name: String
    public let template: String?
    public let description: String?
    public let agent: String?
    public let source: String?
    public let subtask: Bool?

    private enum CodingKeys: String, CodingKey {
        case name, template, description, agent, source, subtask
    }

    public init(
        name: String,
        template: String? = nil,
        description: String? = nil,
        agent: String? = nil,
        source: String? = nil,
        subtask: Bool? = nil
    ) {
        self.name = name
        self.template = template
        self.description = description
        self.agent = agent
        self.source = source
        self.subtask = subtask
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        template = try? container.decode(String.self, forKey: .template)
        description = try container.decodeIfPresent(String.self, forKey: .description)
        agent = try container.decodeIfPresent(String.self, forKey: .agent)
        source = try container.decodeIfPresent(String.self, forKey: .source)
        subtask = try container.decodeIfPresent(Bool.self, forKey: .subtask)
    }
}

public nonisolated struct V2SkillDTO: Decodable, Sendable {
    public let id: String?
    public let name: String
    public let description: String?
    public let slash: Bool?
    public let location: String?
    public let path: String?
    public let content: String?

    public init(
        id: String? = nil,
        name: String,
        description: String? = nil,
        slash: Bool? = nil,
        location: String? = nil,
        path: String? = nil,
        content: String? = nil
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.slash = slash
        self.location = location
        self.path = path
        self.content = content
    }
}

public nonisolated struct V2AgentDTO: Decodable, Sendable {
    public let id: String
    public let description: String?
    public let mode: String
    public let hidden: Bool?

    public init(
        id: String,
        description: String?,
        mode: String,
        hidden: Bool?
    ) {
        self.id = id
        self.description = description
        self.mode = mode
        self.hidden = hidden
    }
}

public nonisolated struct V2ProviderInfoDTO: Decodable, Sendable {
    public let id: String
    public let name: String?
    public let disabled: Bool?

    public init(
        id: String,
        name: String?,
        disabled: Bool?
    ) {
        self.id = id
        self.name = name
        self.disabled = disabled
    }
}

public nonisolated struct V2ModelInfoDTO: Decodable, Sendable {
    public let id: String
    public let modelID: String?
    public let providerID: String
    public let name: String?
    public let enabled: Bool?
    public let status: String?
    public let variants: [V2ModelVariantDTO]
    public let capabilities: V2ModelCapabilitiesDTO?
    public let limit: V2ModelLimitDTO?

    private enum CodingKeys: String, CodingKey {
        case id
        case modelID
        case providerID
        case name
        case enabled
        case status
        case variants
        case capabilities
        case limit
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        modelID = try container.decodeIfPresent(String.self, forKey: .modelID)
        providerID = try container.decode(String.self, forKey: .providerID)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        enabled = try container.decodeIfPresent(Bool.self, forKey: .enabled)
        status = try container.decodeIfPresent(String.self, forKey: .status)
        variants = try container.decodeIfPresent([V2ModelVariantDTO].self, forKey: .variants) ?? []
        capabilities = try container.decodeIfPresent(V2ModelCapabilitiesDTO.self, forKey: .capabilities)
        limit = try container.decodeIfPresent(V2ModelLimitDTO.self, forKey: .limit)
    }
}

public nonisolated struct V2ModelVariantDTO: Decodable, Sendable {
    public let id: String?

    public init(
        id: String?
    ) {
        self.id = id
    }
}

public nonisolated struct V2ModelCapabilitiesDTO: Decodable, Sendable {
    public let tools: Bool?
    public let input: [String]

    private enum CodingKeys: String, CodingKey {
        case tools
        case input
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        tools = try container.decodeIfPresent(Bool.self, forKey: .tools)
        input = try container.decodeIfPresent([String].self, forKey: .input) ?? []
    }
}

public nonisolated struct V2ModelLimitDTO: Decodable, Sendable {
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
