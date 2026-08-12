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
