import Foundation

enum ComposerMentionRouting {
    static func atSuggestionQuery(
        _ text: String,
        cursor: Int,
        mentions: [AgentMention] = [],
        fileMentions: [FileMention] = []
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
        // A completed mention at the caret is not an open @ query. A caret inside a
        // file path (which may contain spaces) is still that mention.
        if mentions.contains(where: { $0.start == atOffset && $0.end == clamped }) {
            return nil
        }
        if fileMentions.contains(where: { clamped > $0.start && clamped <= $0.end }) {
            return nil
        }
        return token
    }

    static func suggestionMode(
        text: String,
        cursor: Int,
        mentions: [AgentMention] = [],
        fileMentions: [FileMention] = []
    ) -> ComposerSuggestionMode {
        if let atQuery = atSuggestionQuery(text, cursor: cursor, mentions: mentions, fileMentions: fileMentions) {
            return .at(query: atQuery)
        }
        if let slashQuery = ComposerSubmitRouting.slashSuggestionQuery(text) {
            return .slash(query: slashQuery)
        }
        return .none
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
        existingMentions: [AgentMention] = [],
        existingFileMentions: [FileMention] = []
    ) -> MentionInsertResult {
        let inserted = insertToken(text: text, cursor: cursor, token: "@\(agentName)")
        let mention = AgentMention(
            id: mentionID,
            name: agentName,
            start: inserted.atIndex,
            end: inserted.atIndex + inserted.token.count
        )
        return MentionInsertResult(
            text: inserted.text,
            cursor: inserted.cursor,
            mentions: (shiftedAgents(existingMentions, inserted: inserted) + [mention]).sorted { $0.start < $1.start },
            fileMentions: shiftedFiles(existingFileMentions, inserted: inserted)
        )
    }

    static func insertFileMention(
        text: String,
        cursor: Int,
        path: String,
        mentionID: String,
        existingMentions: [AgentMention] = [],
        existingFileMentions: [FileMention] = []
    ) -> MentionInsertResult {
        let inserted = insertToken(text: text, cursor: cursor, token: "@\(path)")
        let mention = FileMention(
            id: mentionID,
            path: path,
            start: inserted.atIndex,
            end: inserted.atIndex + inserted.token.count
        )
        return MentionInsertResult(
            text: inserted.text,
            cursor: inserted.cursor,
            mentions: shiftedAgents(existingMentions, inserted: inserted),
            fileMentions: (shiftedFiles(existingFileMentions, inserted: inserted) + [mention]).sorted { $0.start < $1.start }
        )
    }

    static func reconcileAfterEdit(
        previousText: String,
        previousMentions: [AgentMention],
        proposedText: String,
        proposedCursor: Int,
        previousFileMentions: [FileMention] = []
    ) -> MentionReconcileResult {
        let reconciled = reconcileSpans(
            previousText: previousText,
            previousSpans: spans(agents: previousMentions, files: previousFileMentions),
            proposedText: proposedText,
            proposedCursor: proposedCursor
        )
        return MentionReconcileResult(
            text: reconciled.text,
            cursor: reconciled.cursor,
            mentions: agents(from: reconciled.spans),
            fileMentions: files(from: reconciled.spans)
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

    static func fileParts(text: String, mentions: [FileMention]) -> [FilePartPayload] {
        mentions
            .sorted { $0.start < $1.start }
            .compactMap { mention in
                guard mention.start >= 0, mention.end <= text.count, mention.start < mention.end else {
                    return nil
                }
                let value = substring(text, start: mention.start, end: mention.end)
                guard value == mention.token else { return nil }
                return FilePartPayload(
                    path: mention.path,
                    filename: ProjectPaths.filename(of: mention.path),
                    sourceValue: value,
                    start: mention.start,
                    end: mention.end
                )
            }
    }

    static func trimForSend(
        text: String,
        mentions: [AgentMention],
        fileMentions: [FileMention] = []
    ) -> TrimmedPrompt {
        guard let leadingIndex = text.firstIndex(where: { !$0.isWhitespace }) else {
            return TrimmedPrompt(text: "", agentMentions: [], fileMentions: [])
        }
        guard let trailingIndex = text.lastIndex(where: { !$0.isWhitespace }) else {
            return TrimmedPrompt(text: "", agentMentions: [], fileMentions: [])
        }
        let leading = text.distance(from: text.startIndex, to: leadingIndex)
        let trailing = text.distance(from: text.startIndex, to: trailingIndex)
        let trimmed = substring(text, start: leading, end: trailing + 1)
        func adjust(start: Int, end: Int) -> (Int, Int)? {
            if end <= leading || start > trailing { return nil }
            return (start - leading, end - leading)
        }
        return TrimmedPrompt(
            text: trimmed,
            agentMentions: mentions.compactMap { mention in
                guard let range = adjust(start: mention.start, end: mention.end) else { return nil }
                return AgentMention(id: mention.id, name: mention.name, start: range.0, end: range.1)
            },
            fileMentions: fileMentions.compactMap { mention in
                guard let range = adjust(start: mention.start, end: mention.end) else { return nil }
                return FileMention(id: mention.id, path: mention.path, start: range.0, end: range.1)
            }
        )
    }

    private struct TokenInsertion {
        let text: String
        let cursor: Int
        let atIndex: Int
        let token: String
        let delta: Int
        let replacedUntil: Int
    }

    private static func insertToken(text: String, cursor: Int, token: String) -> TokenInsertion {
        let clamped = max(0, min(cursor, text.count))
        let before = substring(text, start: 0, end: clamped)
        guard let atIndexChar = before.lastIndex(of: "@") else {
            preconditionFailure("No @ token before cursor")
        }
        let atIndex = before.distance(from: before.startIndex, to: atIndexChar)
        let insertion = "\(token) "
        return TokenInsertion(
            text: substring(text, start: 0, end: atIndex) + insertion + substring(text, start: clamped, end: text.count),
            cursor: atIndex + insertion.count,
            atIndex: atIndex,
            token: token,
            delta: insertion.count - (clamped - atIndex),
            replacedUntil: clamped
        )
    }

    private static func shiftedAgents(_ mentions: [AgentMention], inserted: TokenInsertion) -> [AgentMention] {
        mentions.compactMap { mention in
            if mention.end <= inserted.atIndex { return mention }
            if mention.start >= inserted.replacedUntil {
                return AgentMention(
                    id: mention.id,
                    name: mention.name,
                    start: mention.start + inserted.delta,
                    end: mention.end + inserted.delta
                )
            }
            return nil
        }.sorted { $0.start < $1.start }
    }

    private static func shiftedFiles(_ mentions: [FileMention], inserted: TokenInsertion) -> [FileMention] {
        mentions.compactMap { mention in
            if mention.end <= inserted.atIndex { return mention }
            if mention.start >= inserted.replacedUntil {
                return FileMention(
                    id: mention.id,
                    path: mention.path,
                    start: mention.start + inserted.delta,
                    end: mention.end + inserted.delta
                )
            }
            return nil
        }.sorted { $0.start < $1.start }
    }

    private struct MentionSpan {
        let id: String
        let kind: String
        let label: String
        let start: Int
        let end: Int

        func shifted(oldEditEnd: Int, delta: Int) -> MentionSpan {
            guard start >= oldEditEnd else { return self }
            return MentionSpan(id: id, kind: kind, label: label, start: start + delta, end: end + delta)
        }
    }

    private struct SpanReconcile {
        let text: String
        let cursor: Int
        let spans: [MentionSpan]
    }

    private static let agentKind = "agent"
    private static let fileKind = "file"

    private static func spans(agents: [AgentMention], files: [FileMention]) -> [MentionSpan] {
        agents.map { MentionSpan(id: $0.id, kind: agentKind, label: $0.name, start: $0.start, end: $0.end) }
            + files.map { MentionSpan(id: $0.id, kind: fileKind, label: $0.path, start: $0.start, end: $0.end) }
    }

    private static func agents(from spans: [MentionSpan]) -> [AgentMention] {
        spans
            .filter { $0.kind == agentKind }
            .sorted { $0.start < $1.start }
            .map { AgentMention(id: $0.id, name: $0.label, start: $0.start, end: $0.end) }
    }

    private static func files(from spans: [MentionSpan]) -> [FileMention] {
        spans
            .filter { $0.kind == fileKind }
            .sorted { $0.start < $1.start }
            .map { FileMention(id: $0.id, path: $0.label, start: $0.start, end: $0.end) }
    }

    private static func reconcileSpans(
        previousText: String,
        previousSpans: [MentionSpan],
        proposedText: String,
        proposedCursor: Int
    ) -> SpanReconcile {
        if previousText == proposedText {
            return SpanReconcile(
                text: proposedText,
                cursor: max(0, min(proposedCursor, proposedText.count)),
                spans: previousSpans
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
        let overlapping = previousSpans.filter { span in
            span.start < oldEditEnd && span.end > oldEditStart
        }

        if overlapping.isEmpty {
            let delta = proposedText.count - previousText.count
            return SpanReconcile(
                text: proposedText,
                cursor: max(0, min(proposedCursor, proposedText.count)),
                spans: previousSpans.map { $0.shifted(oldEditEnd: oldEditEnd, delta: delta) }
            )
        }

        if overlapping.count == 1 {
            let span = overlapping[0]
            let isInternalEdit = oldEditStart >= span.start && oldEditEnd <= span.end
            let isShrink = proposedText.count < previousText.count
            if isInternalEdit, isShrink {
                var text = previousText
                let start = text.index(text.startIndex, offsetBy: span.start)
                let end = text.index(text.startIndex, offsetBy: span.end)
                text.removeSubrange(start ..< end)
                let removed = span.end - span.start
                let spans = previousSpans
                    .filter { !($0.id == span.id && $0.kind == span.kind) }
                    .map { current in
                        if current.start >= span.end {
                            return MentionSpan(
                                id: current.id,
                                kind: current.kind,
                                label: current.label,
                                start: current.start - removed,
                                end: current.end - removed
                            )
                        }
                        return current
                    }
                return SpanReconcile(
                    text: text,
                    cursor: max(0, min(span.start, text.count)),
                    spans: spans
                )
            }
        }

        let delta = proposedText.count - previousText.count
        let spans = previousSpans
            .filter { span in !overlapping.contains { $0.id == span.id && $0.kind == span.kind } }
            .map { $0.shifted(oldEditEnd: oldEditEnd, delta: delta) }
        return SpanReconcile(
            text: proposedText,
            cursor: max(0, min(proposedCursor, proposedText.count)),
            spans: spans
        )
    }

    private static func substring(_ text: String, start: Int, end: Int) -> String {
        let lower = text.index(text.startIndex, offsetBy: start)
        let upper = text.index(text.startIndex, offsetBy: end)
        return String(text[lower ..< upper])
    }
}
