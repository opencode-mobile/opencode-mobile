import Foundation

/// The part of a long message the timeline shows, and how many of its characters that is.
nonisolated struct MessageTextCut: Equatable, Sendable {
    /// The shown prefix; ends with "…" when a line was cut part way.
    let visibleText: String
    /// Characters of the message up to the end of `visibleText`, without the "…". Leading blank
    /// lines the shown part skips count as shown.
    let shownCharacters: Int
}

/// How much of a user's own message the timeline lays out (#1043).
///
/// Counted from the text alone, with no layout: a line break is one line, and every
/// `charactersPerLine` characters of a line are one more, so a line costs
/// `max(1, ceil(length / charactersPerLine))`. A message costing at most `tolerance` shows in
/// full; a longer one shows the prefix costing at most `limit`. Android has the same rule,
/// constants and test vectors.
nonisolated enum MessageTextLimit {
    static let charactersPerLine = 40
    static let limit = 25
    static let tolerance = 30

    /// A word boundary closer than this to the start of the cut line is not used.
    private static let minimumWordCut = charactersPerLine / 2

    /// How far past the decision pass the search for the first line with content reads. A text
    /// with no content that soon (a multi-MB blank paste) keeps its blank shown part rather than
    /// being read in full; the whole of it is still behind Show full message.
    static let maximumBlankSkip = 64 * 1024

    /// The shown prefix of `text`, or nil when it shows in full.
    ///
    /// One pass that stops as soon as the message is known to be over `tolerance`: it reads at
    /// most about `tolerance * charactersPerLine` characters plus `tolerance` line breaks,
    /// whatever the length of the text. Leading blank lines count toward that decision, but the
    /// shown part starts at the first line with content, so a paste that opens with many blank
    /// lines never shows an empty bubble; `MessageTextCut.shownCharacters` counts the skipped
    /// blank lines as shown.
    static func cut(_ text: some Sequence<Character>) -> MessageTextCut? {
        var iterator = text.makeIterator()
        var consumed: [Character] = []
        consumed.reserveCapacity(tolerance * charactersPerLine + tolerance + 1)
        guard let first = scan(forced: false, next: {
            guard let character = iterator.next() else { return nil }
            consumed.append(character)
            return character
        }) else { return nil }
        if first.contentLineStart == 0 { return first.cut }

        // The characters from the first line with content on: what is left of `consumed`, then
        // the rest of the text.
        var replay: [Character]
        let skipped: Int
        if let contentLineStart = first.contentLineStart {
            skipped = contentLineStart
            replay = Array(consumed[contentLineStart...])
        } else {
            // Everything read so far is blank; keep looking, holding the current line's blanks.
            let lineStart = (consumed.lastIndex(where: { $0 == "\n" || $0 == "\r\n" }) ?? -1) + 1
            var blankCount = lineStart
            var pending = Array(consumed[lineStart...])
            var found = false
            var searched = 0
            while searched < maximumBlankSkip, let character = iterator.next() {
                searched += 1
                if character == "\n" || character == "\r\n" {
                    blankCount += pending.count + 1
                    pending.removeAll(keepingCapacity: true)
                } else {
                    pending.append(character)
                    if !character.isWhitespace {
                        found = true
                        break
                    }
                }
            }
            guard found, blankCount > 0 else { return first.cut }
            skipped = blankCount
            replay = pending
        }
        replay.reverse()
        guard let rest = scan(forced: true, next: { replay.popLast() ?? iterator.next() }) else { return first.cut }
        return MessageTextCut(visibleText: rest.cut.visibleText, shownCharacters: skipped + rest.cut.shownCharacters)
    }

    private struct Scan {
        let cut: MessageTextCut
        /// Start of the first line with a non-blank character among those read, if any.
        let contentLineStart: Int?
    }

    /// Counts the characters `next` yields. Nil when they fit `tolerance`, unless `forced`: then
    /// the part costing at most `limit` is returned even when the rest would fit.
    private static func scan(forced: Bool, next: () -> Character?) -> Scan? {
        var read: [Character] = []
        read.reserveCapacity(tolerance * charactersPerLine + tolerance + 1)
        var used = 0
        var lineStart = 0
        var lineLength = 0
        var wholeEnd = 0
        var overflowStart: Int?
        var overflowBudget = 0
        var isOver = false
        var contentLineStart: Int?

        func lineCost(_ length: Int) -> Int {
            max(1, (length + charactersPerLine - 1) / charactersPerLine)
        }

        /// Records the line that crosses `limit`: the cut keeps what fits of it.
        func markOverflowIfNeeded(cost: Int) {
            guard overflowStart == nil else { return }
            if used + cost <= limit {
                wholeEnd = read.count
            } else {
                overflowStart = lineStart
                overflowBudget = limit - used
            }
        }

        while let character = next() {
            if character == "\n" || character == "\r\n" {
                let cost = lineCost(lineLength)
                markOverflowIfNeeded(cost: cost)
                used += cost
                if used > tolerance {
                    isOver = true
                    break
                }
                read.append(character)
                lineStart = read.count
                lineLength = 0
            } else {
                if contentLineStart == nil, !character.isWhitespace { contentLineStart = lineStart }
                read.append(character)
                lineLength += 1
                let cost = lineCost(lineLength)
                if used + cost > tolerance {
                    markOverflowIfNeeded(cost: cost)
                    isOver = true
                    break
                }
            }
        }
        if !isOver {
            let cost = lineCost(lineLength)
            markOverflowIfNeeded(cost: cost)
            guard forced || used + cost > tolerance else { return nil }
        }

        var visible: ArraySlice<Character>
        var partial: ArraySlice<Character> = []
        if let overflowStart, overflowBudget > 0 {
            let end = overflowStart + overflowBudget * charactersPerLine
            partial = read[overflowStart ..< min(end, read.count)]
            let nextIsSpace = end < read.count && read[end].isWhitespace
            if !nextIsSpace,
               let space = partial.lastIndex(where: \.isWhitespace),
               space - overflowStart > minimumWordCut
            {
                partial = read[overflowStart ..< space]
            }
            partial = trimmedTrailingWhitespace(partial)
            visible = read[0 ..< overflowStart]
        } else {
            visible = read[0 ..< wholeEnd]
        }
        let cut: MessageTextCut
        if partial.isEmpty {
            visible = trimmedTrailingWhitespace(read[0 ..< wholeEnd])
            cut = MessageTextCut(visibleText: String(visible), shownCharacters: visible.count)
        } else {
            cut = MessageTextCut(
                visibleText: String(visible) + String(partial) + "…",
                shownCharacters: visible.count + partial.count
            )
        }
        return Scan(cut: cut, contentLineStart: contentLineStart)
    }

    private static func trimmedTrailingWhitespace(_ characters: ArraySlice<Character>) -> ArraySlice<Character> {
        var end = characters.endIndex
        while end > characters.startIndex, characters[end - 1].isWhitespace {
            end -= 1
        }
        return characters[characters.startIndex ..< end]
    }
}

nonisolated extension MessageTextLimit {
    /// The text markdown detection, parsing and highlighting read for a message: the shown part
    /// of a cut user message, so none of them ever sees the hidden rest (#1043); the whole text
    /// otherwise. A cut bubble then renders like an uncut one up to the cut.
    static func markdownSource(role: MessageRole, text: String) -> String {
        guard role == .user, let cut = cut(text) else { return text }
        return cut.visibleText
    }
}

nonisolated extension Message {
    /// See `MessageTextLimit.markdownSource(role:text:)`.
    var markdownSourceText: String {
        MessageTextLimit.markdownSource(role: role, text: text)
    }
}

nonisolated extension String {
    /// `trimmingCharacters(in: .whitespacesAndNewlines)`, without the Foundation round trip that
    /// copies the whole text on every timeline frame: the text itself when there is nothing to
    /// trim, else a native copy of the trimmed range (#1043).
    var trimmingWhitespaceAndNewlines: String {
        let set = CharacterSet.whitespacesAndNewlines
        let scalars = unicodeScalars
        guard let start = scalars.firstIndex(where: { !set.contains($0) }),
              let last = scalars.lastIndex(where: { !set.contains($0) })
        else { return "" }
        if start == scalars.startIndex, scalars.index(after: last) == scalars.endIndex {
            return self
        }
        return String(scalars[start ... last])
    }
}
