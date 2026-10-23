import Foundation

/// Collapses server-expanded slash-command / skill templates back into a
/// compact chat display: `[/name]` plus the arguments the user typed.
enum CommandInvocationCollapser {
    struct Display: Equatable, Sendable {
        let commandName: String
        let arguments: String
        let text: String
        /// UTF-16 code-unit ranges covering each `[/name]` inside `text`.
        let highlightRanges: [Range<Int>]
    }

    /// Wraps every placeable skill span as `[/name]`. Offsets are checked against `text`; a span
    /// whose text moved falls back to the first unused `/name` occurrence, and one that cannot be
    /// placed is skipped.
    static func collapseSkillMentions(text: String, skillMentions: [SkillMention]) -> Display? {
        guard !skillMentions.isEmpty else { return nil }
        let characters = Array(text)
        var ranges: [Range<Int>] = []
        var names: [String] = []
        for mention in skillMentions.sorted(by: { $0.start < $1.start }) {
            let token = Array(mention.token)
            let fits = mention.start >= 0 && mention.end <= characters.count && mention.start < mention.end
                && Array(characters[mention.start ..< mention.end]) == token
            let range: Range<Int>?
            if fits, !ranges.contains(where: { $0.overlaps(mention.start ..< mention.end) }) {
                range = mention.start ..< mention.end
            } else {
                range = firstFreeOccurrence(of: token, in: characters, taken: ranges)
            }
            guard let range else { continue }
            ranges.append(range)
            names.append(mention.name)
        }
        guard !ranges.isEmpty else { return nil }
        let order = ranges.indices.sorted { ranges[$0].lowerBound < ranges[$1].lowerBound }
        var result = ""
        var highlights: [Range<Int>] = []
        var cursor = 0
        for index in order {
            let range = ranges[index]
            result += String(characters[cursor ..< range.lowerBound])
            let token = "[/\(names[index])]"
            let start = result.utf16.count
            result += token
            highlights.append(start ..< start + token.utf16.count)
            cursor = range.upperBound
        }
        result += String(characters[cursor...])
        return Display(
            commandName: names[order[0]],
            arguments: "",
            text: result,
            highlightRanges: highlights
        )
    }

    /// UTF-16 ranges of every `[/name]` token in a collapsed display text.
    static func tokenRanges(in text: String) -> [Range<Int>] {
        guard let regex = try? NSRegularExpression(pattern: "\\[/[^\\]\\s]+\\]") else { return [] }
        let whole = NSRange(location: 0, length: (text as NSString).length)
        return regex.matches(in: text, range: whole).map { $0.range.location ..< $0.range.location + $0.range.length }
    }

    private static func firstFreeOccurrence(
        of token: [Character],
        in characters: [Character],
        taken: [Range<Int>]
    ) -> Range<Int>? {
        guard !token.isEmpty, characters.count >= token.count else { return nil }
        var start = 0
        while start + token.count <= characters.count {
            let candidate = start ..< start + token.count
            if Array(characters[candidate]) == token,
               !taken.contains(where: { $0.overlaps(candidate) }),
               isWordBounded(candidate, in: characters)
            {
                return candidate
            }
            start += 1
        }
        return nil
    }

    private static func isWordBounded(_ range: Range<Int>, in characters: [Character]) -> Bool {
        let before = range.lowerBound == 0 || characters[range.lowerBound - 1].isWhitespace
        let after = range.upperBound == characters.count || characters[range.upperBound].isWhitespace
        return before && after
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
            highlightRanges: [0 ..< token.utf16.count]
        )
    }
}
