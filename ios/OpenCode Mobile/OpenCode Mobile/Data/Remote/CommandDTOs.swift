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
}

nonisolated struct RunCommandRequestDTO: Codable, Sendable {
    let command: String
    let arguments: String
    let agent: String?
    let model: String?
    let variant: String?
    let parts: [CommandFilePartDTO]

    init(
        command: String,
        arguments: String,
        agent: String? = nil,
        model: String? = nil,
        variant: String? = nil,
        parts: [CommandFilePartDTO] = []
    ) {
        self.command = command
        self.arguments = arguments
        self.agent = agent
        self.model = model
        self.variant = variant
        self.parts = parts
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
    let command: String
    let arguments: String?
    let agent: String?
    let model: V2PromptModelRefDTO?
    let files: [V2PromptFileDTO]?
    let delivery: String

    init(
        command: String,
        arguments: String? = nil,
        agent: String? = nil,
        model: V2PromptModelRefDTO? = nil,
        files: [V2PromptFileDTO]? = nil,
        delivery: String = "steer"
    ) {
        self.command = command
        self.arguments = arguments
        self.agent = agent
        self.model = model
        self.files = files
        self.delivery = delivery
    }

    private enum CodingKeys: String, CodingKey {
        case command
        case arguments
        case agent
        case model
        case files
        case delivery
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(command, forKey: .command)
        try container.encodeIfPresent(arguments, forKey: .arguments)
        try container.encodeIfPresent(agent, forKey: .agent)
        try container.encodeIfPresent(model, forKey: .model)
        try container.encodeIfPresent(files, forKey: .files)
        try container.encode(delivery, forKey: .delivery)
    }
}
