import Foundation

enum ServerCommandSource: Equatable, Sendable {
    case command
    case mcp
    case skill
}

struct ServerCommand: Equatable, Sendable, Identifiable {
    var id: String { name }

    let name: String
    let description: String?
    let source: ServerCommandSource

    init(name: String, description: String? = nil, source: ServerCommandSource = .command) {
        self.name = name
        self.description = description
        self.source = source
    }
}

struct CommandRunOptions: Equatable, Sendable {
    let command: String
    let arguments: String
    let attachments: [Attachment]
    let model: PromptModelSelection?
    let variant: String?
    let mode: String?

    init(
        command: String,
        arguments: String,
        attachments: [Attachment] = [],
        model: PromptModelSelection? = nil,
        variant: String? = nil,
        mode: String? = nil
    ) {
        self.command = command
        self.arguments = arguments
        self.attachments = attachments
        self.model = model
        self.variant = variant
        self.mode = mode
    }
}

enum ComposerSubmitIntent: Equatable, Sendable {
    case prompt(text: String)
    case serverCommand(command: String, arguments: String)
}

enum CommandError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case decoding(String)
}
