import Foundation

nonisolated struct V2CommandDTO: Decodable, Sendable {
    let name: String
    let template: String?
    let description: String?
    let agent: String?
    let source: String?
    let subtask: Bool?

    private enum CodingKeys: String, CodingKey {
        case name, template, description, agent, source, subtask
    }

    init(
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

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        template = try? container.decode(String.self, forKey: .template)
        description = try container.decodeIfPresent(String.self, forKey: .description)
        agent = try container.decodeIfPresent(String.self, forKey: .agent)
        source = try container.decodeIfPresent(String.self, forKey: .source)
        subtask = try container.decodeIfPresent(Bool.self, forKey: .subtask)
    }
}

nonisolated struct V2SkillDTO: Decodable, Sendable {
    let name: String
    let description: String?
    let slash: Bool?
    let location: String?
    let content: String?
}

nonisolated struct V2AgentDTO: Decodable, Sendable {
    let id: String
    let description: String?
    let mode: String
    let hidden: Bool?
}

nonisolated struct V2ProviderInfoDTO: Decodable, Sendable {
    let id: String
    let name: String?
    let disabled: Bool?
}

nonisolated struct V2ModelInfoDTO: Decodable, Sendable {
    let id: String
    let modelID: String?
    let providerID: String
    let name: String?
    let enabled: Bool?
    let status: String?
    let variants: [V2ModelVariantDTO]
    let capabilities: V2ModelCapabilitiesDTO?
    let limit: V2ModelLimitDTO?

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

    init(from decoder: Decoder) throws {
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

nonisolated struct V2ModelVariantDTO: Decodable, Sendable {
    let id: String?
}

nonisolated struct V2ModelCapabilitiesDTO: Decodable, Sendable {
    let tools: Bool?
    let input: [String]

    private enum CodingKeys: String, CodingKey {
        case tools
        case input
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        tools = try container.decodeIfPresent(Bool.self, forKey: .tools)
        input = try container.decodeIfPresent([String].self, forKey: .input) ?? []
    }
}

nonisolated struct V2ModelLimitDTO: Decodable, Sendable {
    let context: Int64?
    let output: Int64?
}
