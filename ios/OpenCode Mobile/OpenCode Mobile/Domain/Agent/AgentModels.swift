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

struct AgentPartPayload: Equatable, Sendable {
    let name: String
    let sourceValue: String
    let start: Int
    let end: Int
}

struct MentionInsertResult: Equatable, Sendable {
    let text: String
    let cursor: Int
    let mentions: [AgentMention]
}

struct MentionReconcileResult: Equatable, Sendable {
    let text: String
    let cursor: Int
    let mentions: [AgentMention]
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
