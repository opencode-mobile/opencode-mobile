import Foundation

enum ComposerSubmitRouting {
    private static let slashSuggestionPattern = /^\/(\S*)$/

    static func resolve(text: String, serverCommandNames: Set<String>) -> ComposerSubmitIntent {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("/") else {
            return .prompt(text: trimmed)
        }
        let parts = trimmed.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false)
        let head = String(parts[0].dropFirst())
        guard !head.isEmpty, serverCommandNames.contains(head) else {
            return .prompt(text: trimmed)
        }
        let arguments = parts.count > 1 ? String(parts[1]) : ""
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
