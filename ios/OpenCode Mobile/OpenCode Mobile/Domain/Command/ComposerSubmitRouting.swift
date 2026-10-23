import Foundation

enum ComposerSubmitRouting {
    static func resolve(text: String, serverCommandNames: Set<String>) -> ComposerSubmitIntent {
        resolve(text: text, commands: serverCommandNames.map { ServerCommand(name: $0) })
    }

    static func resolve(text: String, commands: [ServerCommand]) -> ComposerSubmitIntent {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("/") else {
            return .prompt(text: trimmed)
        }
        let parts = trimmed.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false)
        let head = String(parts[0].dropFirst())
        guard !head.isEmpty, let match = commands.first(where: { $0.name == head }) else {
            return .prompt(text: trimmed)
        }
        let arguments = parts.count > 1 ? String(parts[1]) : ""
        // A v2 skill is admitted on the prompt as a `/name` span, never on the command route.
        if isPromptSkill(match) {
            return .prompt(text: trimmed)
        }
        return .serverCommand(command: head, arguments: arguments)
    }

    /// The v2 skill a hand-typed leading `/name` names, so the send can admit it as a span.
    static func leadingSkill(text: String, commands: [ServerCommand]) -> ServerCommand? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("/") else { return nil }
        let head = String(trimmed.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false)[0].dropFirst())
        guard !head.isEmpty, let match = commands.first(where: { $0.name == head }), isPromptSkill(match) else {
            return nil
        }
        return match
    }

    static func isPromptSkill(_ command: ServerCommand) -> Bool {
        guard command.source == .skill,
              let skillID = command.skillID?.trimmingCharacters(in: .whitespacesAndNewlines)
        else { return false }
        return !skillID.isEmpty
    }

    /// The open `/` query when the caret sits at the end of `text`.
    static func slashSuggestionQuery(_ text: String) -> String? {
        ComposerMentionRouting.slashSuggestionQuery(text, cursor: text.count)
    }

    /// Slash suggestions for an open query: commands only at the start of the draft, v2 skills
    /// anywhere, and never a skill already placed in the draft.
    static func slashSuggestions(
        _ commands: [ServerCommand],
        query: String,
        isLeading: Bool,
        skillMentions: [SkillMention]
    ) -> [ServerCommand] {
        let placed = Set(skillMentions.map(\.skillID))
        return filterCommands(commands, query: query).filter { command in
            if isPromptSkill(command) {
                return !placed.contains(command.skillID ?? "")
            }
            return isLeading
        }
    }

    static func filterCommands(_ commands: [ServerCommand], query: String) -> [ServerCommand] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if needle.isEmpty {
            return commands
        }
        return commands.filter { command in
            command.name.lowercased().contains(needle)
                || (command.description?.lowercased().contains(needle) == true)
        }
    }
}
