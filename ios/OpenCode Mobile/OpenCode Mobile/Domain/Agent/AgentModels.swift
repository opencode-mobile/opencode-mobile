import Foundation

struct SelectableAgent: Equatable, Sendable, Identifiable {
    let id: String
    let displayName: String
    let description: String?

    init(id: String, displayName: String, description: String? = nil) {
        self.id = id
        self.displayName = displayName
        self.description = description
    }

    static let defaultID = "build"

    static let fallback: [SelectableAgent] = [
        SelectableAgent(id: "build", displayName: "Build"),
        SelectableAgent(id: "plan", displayName: "Plan"),
    ]

    static func displayName(for id: String) -> String {
        TitleCaseIdentifier.displayName(for: id)
    }
}

struct MentionableAgent: Equatable, Sendable, Identifiable {
    var id: String { name }

    let name: String
    let description: String?

    init(name: String, description: String? = nil) {
        self.name = name
        self.description = description
    }
}

struct AgentMention: Equatable, Sendable, Identifiable, Codable {
    let id: String
    let name: String
    let start: Int
    let end: Int

    var token: String { "@\(name)" }
}

/// A project file referenced from the composer. Separate from an agent mention and from an Attachment.
struct FileMention: Equatable, Sendable, Identifiable, Codable {
    let id: String
    let path: String
    let start: Int
    let end: Int

    var token: String { "@\(path)" }
}

/// A v2 skill placed in the composer as a `/name` span. `skillID` is the wire id; `id` is the span id.
struct SkillMention: Equatable, Sendable, Identifiable, Codable {
    let id: String
    let skillID: String
    let name: String
    let start: Int
    let end: Int

    var token: String { "/\(name)" }
}

struct SkillPartPayload: Equatable, Sendable {
    let skillID: String
    let name: String
    let sourceValue: String
    let start: Int
    let end: Int
}

struct AgentPartPayload: Equatable, Sendable {
    let name: String
    let sourceValue: String
    let start: Int
    let end: Int
}

struct FilePartPayload: Equatable, Sendable {
    let path: String
    let filename: String
    let sourceValue: String
    let start: Int
    let end: Int
}

struct MentionInsertResult: Equatable, Sendable {
    let text: String
    let cursor: Int
    let mentions: [AgentMention]
    let fileMentions: [FileMention]
    let skillMentions: [SkillMention]

    init(
        text: String,
        cursor: Int,
        mentions: [AgentMention],
        fileMentions: [FileMention] = [],
        skillMentions: [SkillMention] = []
    ) {
        self.text = text
        self.cursor = cursor
        self.mentions = mentions
        self.fileMentions = fileMentions
        self.skillMentions = skillMentions
    }
}

struct MentionReconcileResult: Equatable, Sendable {
    let text: String
    let cursor: Int
    let mentions: [AgentMention]
    let fileMentions: [FileMention]
    let skillMentions: [SkillMention]

    init(
        text: String,
        cursor: Int,
        mentions: [AgentMention],
        fileMentions: [FileMention] = [],
        skillMentions: [SkillMention] = []
    ) {
        self.text = text
        self.cursor = cursor
        self.mentions = mentions
        self.fileMentions = fileMentions
        self.skillMentions = skillMentions
    }
}

struct TrimmedPrompt: Equatable, Sendable {
    let text: String
    let agentMentions: [AgentMention]
    let fileMentions: [FileMention]
    let skillMentions: [SkillMention]

    init(
        text: String,
        agentMentions: [AgentMention],
        fileMentions: [FileMention] = [],
        skillMentions: [SkillMention] = []
    ) {
        self.text = text
        self.agentMentions = agentMentions
        self.fileMentions = fileMentions
        self.skillMentions = skillMentions
    }
}

enum ComposerSuggestionMode: Equatable, Sendable {
    case none
    case slash(query: String)
    case at(query: String)
}

enum AgentError: Error, Equatable {
    case transport(String)
    case http(statusCode: Int, message: String?)
    case decoding(String)
}
