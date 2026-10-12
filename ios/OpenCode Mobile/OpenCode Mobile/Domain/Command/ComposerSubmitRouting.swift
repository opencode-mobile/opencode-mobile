import Foundation

enum ComposerSubmitRouting {
    private static let slashSuggestionPattern = /^\/(\S*)$/

    static func resolve(
        text: String,
        serverCommandNames: Set<String>,
        allowsSlashCommands: Bool = true
    ) -> ComposerSubmitIntent {
        resolve(
            text: text,
            commands: serverCommandNames.map { ServerCommand(name: $0) },
            allowsSlashCommands: allowsSlashCommands
        )
    }

    static func resolve(
        text: String,
        commands: [ServerCommand],
        allowsSlashCommands: Bool = true
    ) -> ComposerSubmitIntent {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !allowsSlashCommands && trimmed.hasPrefix("/") {
            return .ignored
        }
        guard trimmed.hasPrefix("/") else {
            return .prompt(text: trimmed)
        }
        let parts = trimmed.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false)
        let head = String(parts[0].dropFirst())
        guard !head.isEmpty, let match = commands.first(where: { $0.name == head }) else {
            return .prompt(text: trimmed)
        }
        let arguments = parts.count > 1 ? String(parts[1]) : ""
        if match.source == .skill, let skillID = match.skillID?.trimmingCharacters(in: .whitespacesAndNewlines), !skillID.isEmpty {
            return .skillPrompt(skillID: skillID, text: arguments)
        }
        return .serverCommand(command: head, arguments: arguments)
    }

    static func slashSuggestionQuery(_ text: String) -> String? {
        guard let match = text.wholeMatch(of: slashSuggestionPattern) else {
            return nil
        }
        return String(match.1)
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

    static func insertSelection(commandName: String) -> String {
        "/\(commandName) "
    }
}
