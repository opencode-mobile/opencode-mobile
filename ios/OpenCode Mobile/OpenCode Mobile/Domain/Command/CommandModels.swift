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
    /// Expanded prompt template from `GET /command`; used to collapse chat display.
    let template: String?
    /// v2 skill id used to admit the skill on a prompt. Absent for real commands.
    let skillID: String?

    init(
        name: String,
        description: String? = nil,
        source: ServerCommandSource = .command,
        template: String? = nil,
        skillID: String? = nil
    ) {
        self.name = name
        self.description = description
        self.source = source
        self.template = template
        self.skillID = skillID
    }
}

struct CommandRunOptions: Equatable, Sendable {
    let command: String
    let arguments: String
    let attachments: [Attachment]
    let model: PromptModelSelection?
    let variant: String?
    let mode: String?
    let delivery: FollowUpDelivery
    /// v2 skills placed in the draft; ride along on the command route without offsets.
    let skillIDs: [String]

    init(
        command: String,
        arguments: String,
        attachments: [Attachment] = [],
        model: PromptModelSelection? = nil,
        variant: String? = nil,
        mode: String? = nil,
        delivery: FollowUpDelivery = .steer,
        skillIDs: [String] = []
    ) {
        self.command = command
        self.arguments = arguments
        self.attachments = attachments
        self.model = model
        self.variant = variant
        self.mode = mode
        self.delivery = delivery
        self.skillIDs = skillIDs
    }
}

enum ComposerSubmitIntent: Equatable, Sendable {
    /// Plain prompt. Skills travel as `/name` spans on the prompt, never on the command route.
    case prompt(text: String)
    case serverCommand(command: String, arguments: String)
}

nonisolated enum CommandError: Error, Equatable {
    case transport(TransportFailure)
    case http(statusCode: Int, message: String?)
    case decoding(String)
}
