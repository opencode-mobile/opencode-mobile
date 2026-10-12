import Foundation

enum ComposerMentionRouting {
    static func atSuggestionQuery(
        _ text: String,
        cursor: Int,
        mentions: [AgentMention] = []
    ) -> String? {
        let clamped = max(0, min(cursor, text.count))
        let before = substring(text, start: 0, end: clamped)
        guard let atIndex = before.lastIndex(of: "@") else { return nil }
        let atOffset = before.distance(from: before.startIndex, to: atIndex)
        if atOffset > 0 {
            let previousIndex = before.index(before.startIndex, offsetBy: atOffset - 1)
            if !before[previousIndex].isWhitespace { return nil }
        }
        let tokenStart = before.index(after: atIndex)
        let token = String(before[tokenStart...])
        if token.contains(where: \.isWhitespace) { return nil }
        // A completed structured mention at the caret is not an open @ query.
        if mentions.contains(where: { $0.start == atOffset && $0.end == clamped }) {
            return nil
        }
        return token
    }

    static func suggestionMode(
        text: String,
        cursor: Int,
        mentions: [AgentMention] = [],
        allowsMentions: Bool = true,
        allowsSlashCommands: Bool = true
    ) -> ComposerSuggestionMode {
        if allowsMentions, let atQuery = atSuggestionQuery(text, cursor: cursor, mentions: mentions) {
            return .at(query: atQuery)
        }
        if allowsSlashCommands, let slashQuery = ComposerSubmitRouting.slashSuggestionQuery(text) {
            return .slash(query: slashQuery)
        }
        return .none
    }

    /// Drops structured mentions and unresolved `@tokens` (start, or after
    /// whitespace, then a non-whitespace run). An address such as `a@b.com`
    /// stays. A bare `@` stays unless it is the end of the text.
    static func withoutMentionTokens(text: String, mentions: [AgentMention] = []) -> String {
        var result = text
        for mention in mentions.sorted(by: { $0.start > $1.start }) {
            guard mention.start >= 0, mention.end <= result.count, mention.start <= mention.end else { continue }
            let dropFollowingSpace =
                mention.start > 0
                && isWhitespace(result, at: mention.start - 1)
                && mention.end < result.count
                && isWhitespace(result, at: mention.end)
            let end = dropFollowingSpace ? mention.end + 1 : mention.end
            result = substring(result, start: 0, end: mention.start) + substring(result, start: end, end: result.count)
        }
        return stripUnresolvedAtTokens(result)
    }

    private static func stripUnresolvedAtTokens(_ text: String) -> String {
        var out = ""
        var index = 0
        let characters = Array(text)
        while index < characters.count {
            let atBoundary = characters[index] == "@" && (index == 0 || characters[index - 1].isWhitespace)
            if !atBoundary {
                out.append(characters[index])
                index += 1
                continue
            }
            var end = index + 1
            while end < characters.count && !characters[end].isWhitespace {
                end += 1
            }
            let hasName = end > index + 1
            let isTrailingBare = !hasName && end == characters.count
            if !hasName && !isTrailingBare {
                out.append(characters[index])
                index += 1
                continue
            }
            index = end
            if index < characters.count && characters[index].isWhitespace && (out.isEmpty || out.last?.isWhitespace == true) {
                index += 1
            }
            if out.isEmpty {
                while index < characters.count && characters[index].isWhitespace {
                    index += 1
                }
            }
        }
        return out.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func isWhitespace(_ text: String, at offset: Int) -> Bool {
        let index = text.index(text.startIndex, offsetBy: offset)
        return text[index].isWhitespace
    }

    static func filterAgents(_ agents: [MentionableAgent], query: String) -> [MentionableAgent] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if needle.isEmpty { return agents }
        return agents.filter { agent in
            agent.name.lowercased().contains(needle)
                || (agent.description?.lowercased().contains(needle) == true)
        }
    }

    static func insertMention(
        text: String,
        cursor: Int,
        agentName: String,
        mentionID: String,
        existingMentions: [AgentMention] = []
    ) -> MentionInsertResult {
        let clamped = max(0, min(cursor, text.count))
        let before = substring(text, start: 0, end: clamped)
        guard let atIndexChar = before.lastIndex(of: "@") else {
            preconditionFailure("No @ token before cursor")
        }
        let atIndex = before.distance(from: before.startIndex, to: atIndexChar)
        let token = "@\(agentName)"
        let insertion = "\(token) "
        let newText = substring(text, start: 0, end: atIndex) + insertion + substring(text, start: clamped, end: text.count)
        let delta = insertion.count - (clamped - atIndex)
        let shifted = existingMentions.compactMap { mention -> AgentMention? in
            if mention.end <= atIndex { return mention }
            if mention.start >= clamped {
                return AgentMention(
                    id: mention.id,
                    name: mention.name,
                    start: mention.start + delta,
                    end: mention.end + delta
                )
            }
            return nil
        }
        let inserted = AgentMention(id: mentionID, name: agentName, start: atIndex, end: atIndex + token.count)
        let mentions = (shifted + [inserted]).sorted { $0.start < $1.start }
        return MentionInsertResult(text: newText, cursor: atIndex + insertion.count, mentions: mentions)
    }

    static func reconcileAfterEdit(
        previousText: String,
        previousMentions: [AgentMention],
        proposedText: String,
        proposedCursor: Int
    ) -> MentionReconcileResult {
        if previousText == proposedText {
            return MentionReconcileResult(
                text: proposedText,
                cursor: max(0, min(proposedCursor, proposedText.count)),
                mentions: previousMentions
            )
        }

        let prefix = previousText.commonPrefix(with: proposedText).count
        let maxSuffix = min(previousText.count - prefix, proposedText.count - prefix)
        var suffix = 0
        while suffix < maxSuffix {
            let previousIndex = previousText.index(previousText.endIndex, offsetBy: -(suffix + 1))
            let proposedIndex = proposedText.index(proposedText.endIndex, offsetBy: -(suffix + 1))
            if previousText[previousIndex] != proposedText[proposedIndex] { break }
            suffix += 1
        }

        let oldEditStart = prefix
        let oldEditEnd = previousText.count - suffix
        let overlapping = previousMentions.filter { mention in
            mention.start < oldEditEnd && mention.end > oldEditStart
        }

        if overlapping.isEmpty {
            let delta = proposedText.count - previousText.count
            let mentions = previousMentions.map { mention in
                if mention.start >= oldEditEnd {
                    return AgentMention(
                        id: mention.id,
                        name: mention.name,
                        start: mention.start + delta,
                        end: mention.end + delta
                    )
                }
                return mention
            }
            return MentionReconcileResult(
                text: proposedText,
                cursor: max(0, min(proposedCursor, proposedText.count)),
                mentions: mentions
            )
        }

        // Atomic delete: a shrink entirely inside one mention removes that whole token.
        if overlapping.count == 1 {
            let mention = overlapping[0]
            let isInternalEdit = oldEditStart >= mention.start && oldEditEnd <= mention.end
            let isShrink = proposedText.count < previousText.count
            if isInternalEdit, isShrink {
                var text = previousText
                let start = text.index(text.startIndex, offsetBy: mention.start)
                let end = text.index(text.startIndex, offsetBy: mention.end)
                text.removeSubrange(start ..< end)
                let removedLength = mention.end - mention.start
                let mentions = previousMentions
                    .filter { $0.id != mention.id }
                    .map { current in
                        if current.start >= mention.end {
                            return AgentMention(
                                id: current.id,
                                name: current.name,
                                start: current.start - removedLength,
                                end: current.end - removedLength
                            )
                        }
                        return current
                    }
                return MentionReconcileResult(
                    text: text,
                    cursor: max(0, min(mention.start, text.count)),
                    mentions: mentions
                )
            }
        }

        // Broader edits: keep proposed text and drop overlapping mentions.
        let delta = proposedText.count - previousText.count
        let overlappingIDs = Set(overlapping.map(\.id))
        let mentions = previousMentions
            .filter { !overlappingIDs.contains($0.id) }
            .map { mention in
                if mention.start >= oldEditEnd {
                    return AgentMention(
                        id: mention.id,
                        name: mention.name,
                        start: mention.start + delta,
                        end: mention.end + delta
                    )
                }
                return mention
            }
        return MentionReconcileResult(
            text: proposedText,
            cursor: max(0, min(proposedCursor, proposedText.count)),
            mentions: mentions
        )
    }

    static func agentParts(text: String, mentions: [AgentMention]) -> [AgentPartPayload] {
        mentions
            .sorted { $0.start < $1.start }
            .compactMap { mention in
                guard mention.start >= 0, mention.end <= text.count, mention.start < mention.end else {
                    return nil
                }
                let value = substring(text, start: mention.start, end: mention.end)
                guard value == mention.token else { return nil }
                return AgentPartPayload(
                    name: mention.name,
                    sourceValue: value,
                    start: mention.start,
                    end: mention.end
                )
            }
    }

    static func trimForSend(text: String, mentions: [AgentMention]) -> (text: String, mentions: [AgentMention]) {
        guard let leadingIndex = text.firstIndex(where: { !$0.isWhitespace }) else {
            return ("", [])
        }
        guard let trailingIndex = text.lastIndex(where: { !$0.isWhitespace }) else {
            return ("", [])
        }
        let leading = text.distance(from: text.startIndex, to: leadingIndex)
        let trailing = text.distance(from: text.startIndex, to: trailingIndex)
        let trimmed = substring(text, start: leading, end: trailing + 1)
        let adjusted = mentions.compactMap { mention -> AgentMention? in
            if mention.end <= leading || mention.start > trailing { return nil }
            return AgentMention(
                id: mention.id,
                name: mention.name,
                start: mention.start - leading,
                end: mention.end - leading
            )
        }
        return (trimmed, adjusted)
    }

    private static func substring(_ text: String, start: Int, end: Int) -> String {
        let lower = text.index(text.startIndex, offsetBy: start)
        let upper = text.index(text.startIndex, offsetBy: end)
        return String(text[lower ..< upper])
    }
}
