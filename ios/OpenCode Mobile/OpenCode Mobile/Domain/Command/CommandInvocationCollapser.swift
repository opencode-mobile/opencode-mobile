import Foundation

/// Collapses server-expanded slash-command / skill templates back into a
/// compact chat display: `[/name]` plus the arguments the user typed.
enum CommandInvocationCollapser {
    struct Display: Equatable, Sendable {
        let commandName: String
        let arguments: String
        let text: String
        /// UTF-16 code-unit range covering `[/name]` inside `text`.
        let highlightRange: Range<Int>
    }

    static func collapse(text: String, commands: [ServerCommand]) -> Display? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !commands.isEmpty else { return nil }

        if let slash = fromSlashForm(trimmed, commandNames: Set(commands.map(\.name))) {
            return slash
        }

        let withTemplates: [(ServerCommand, String)] = commands.compactMap { command in
            guard let template = command.template?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !template.isEmpty
            else { return nil }
            return (command, template)
        }
        .sorted { $0.1.count > $1.1.count }

        for (command, template) in withTemplates {
            if trimmed == template {
                return display(commandName: command.name, arguments: "")
            }
            guard trimmed.hasPrefix(template) else { continue }
            let remainder = String(trimmed.dropFirst(template.count))
            let arguments: String
            if remainder.hasPrefix("\n\n") {
                arguments = String(remainder.dropFirst(2)).trimmingCharacters(in: .whitespacesAndNewlines)
            } else if remainder.hasPrefix("\n") {
                arguments = String(remainder.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines)
            } else if remainder.hasPrefix(" ") {
                arguments = remainder.trimmingCharacters(in: .whitespacesAndNewlines)
            } else {
                continue
            }
            return display(commandName: command.name, arguments: arguments)
        }
        return nil
    }

    private static func fromSlashForm(_ trimmed: String, commandNames: Set<String>) -> Display? {
        guard trimmed.hasPrefix("/") else { return nil }
        let parts = trimmed.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: false)
        let name = String(parts[0].dropFirst())
        guard !name.isEmpty, commandNames.contains(name) else { return nil }
        let arguments = parts.count > 1 ? String(parts[1]).trimmingCharacters(in: .whitespacesAndNewlines) : ""
        return display(commandName: name, arguments: arguments)
    }

    private static func display(commandName: String, arguments: String) -> Display {
        let token = "[/\(commandName)]"
        let text = arguments.isEmpty ? token : "\(token) \(arguments)"
        // Token is ASCII; UTF-16 length matches Character count.
        return Display(
            commandName: commandName,
            arguments: arguments,
            text: text,
            highlightRange: 0 ..< token.utf16.count
        )
    }
}
