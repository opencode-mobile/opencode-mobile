import Foundation

nonisolated struct CommandDTO: Codable, Sendable {
    let name: String
    let description: String?
    let agent: String?
    let model: String?
    let source: String?
    let template: String?
    let subtask: Bool?
    let hints: [String]?

    private enum CodingKeys: String, CodingKey {
        case name, description, agent, model, source, template, subtask, hints
    }

    init(
        name: String,
        description: String? = nil,
        agent: String? = nil,
        model: String? = nil,
        source: String? = nil,
        template: String? = nil,
        subtask: Bool? = nil,
        hints: [String]? = nil
    ) {
        self.name = name
        self.description = description
        self.agent = agent
        self.model = model
        self.source = source
        self.template = template
        self.subtask = subtask
        self.hints = hints
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        description = try container.decodeIfPresent(String.self, forKey: .description)
        agent = try container.decodeIfPresent(String.self, forKey: .agent)
        model = try container.decodeIfPresent(String.self, forKey: .model)
        source = try container.decodeIfPresent(String.self, forKey: .source)
        template = try? container.decode(String.self, forKey: .template)
        subtask = try container.decodeIfPresent(Bool.self, forKey: .subtask)
        hints = try container.decodeIfPresent([String].self, forKey: .hints)
    }
}

nonisolated struct RunCommandRequestDTO: Codable, Sendable {
    let command: String
    let arguments: String
    let agent: String?
    let model: String?
    let variant: String?
    let parts: [CommandFilePartDTO]
    let messageID: String?

    init(
        command: String,
        arguments: String,
        agent: String? = nil,
        model: String? = nil,
        variant: String? = nil,
        parts: [CommandFilePartDTO] = [],
        messageID: String? = nil
    ) {
        self.command = command
        self.arguments = arguments
        self.agent = agent
        self.model = model
        self.variant = variant
        self.parts = parts
        self.messageID = messageID
    }
}

nonisolated struct CommandFilePartDTO: Codable, Sendable {
    let type: String
    let mime: String
    let url: String
    let filename: String?
    let id: String?

    init(
        type: String = "file",
        mime: String,
        url: String,
        filename: String? = nil,
        id: String? = nil
    ) {
        self.type = type
        self.mime = mime
        self.url = url
        self.filename = filename
        self.id = id
    }
}

nonisolated struct V2RunCommandRequestDTO: Encodable, Sendable {
    let name: String
    let text: String
    let files: [V2PromptFileDTO]?
    let skills: [V2PromptSkillDTO]?
    let delivery: String

    init(
        name: String,
        text: String,
        files: [V2PromptFileDTO]? = nil,
        skills: [V2PromptSkillDTO]? = nil,
        delivery: String = "steer"
    ) {
        self.name = name
        self.text = text
        self.files = files
        self.skills = skills
        self.delivery = delivery
    }

    private enum CodingKeys: String, CodingKey {
        case name
        case text
        case files
        case skills
        case delivery
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(name, forKey: .name)
        try container.encode(text, forKey: .text)
        try container.encodeIfPresent(files, forKey: .files)
        try container.encodeIfPresent(skills, forKey: .skills)
        try container.encode(delivery, forKey: .delivery)
    }
}
