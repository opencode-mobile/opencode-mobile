import Foundation

/// The part of a long message the timeline shows, and how many of its characters that is.
nonisolated struct MessageTextCut: Equatable, Sendable {
    /// The shown prefix; ends with "…" when a line was cut part way.
    let visibleText: String
    /// Characters of the message kept in `visibleText`, without the "…".
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

    /// The shown prefix of `text`, or nil when it shows in full.
    ///
    /// One pass that stops as soon as the message is known to be over `tolerance`: it reads at
    /// most about `tolerance * charactersPerLine` characters plus `tolerance` line breaks,
    /// whatever the length of the text.
    static func cut(_ text: some Sequence<Character>) -> MessageTextCut? {
        var read: [Character] = []
        read.reserveCapacity(tolerance * charactersPerLine + tolerance + 1)
        var used = 0
        var lineStart = 0
        var lineLength = 0
        var wholeEnd = 0
        var overflowStart: Int?
        var overflowBudget = 0
        var isOver = false

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

        for character in text {
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
            guard used + cost > tolerance else { return nil }
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
        if partial.isEmpty {
            visible = trimmedTrailingWhitespace(read[0 ..< wholeEnd])
            return MessageTextCut(visibleText: String(visible), shownCharacters: visible.count)
        }
        return MessageTextCut(
            visibleText: String(visible) + String(partial) + "…",
            shownCharacters: visible.count + partial.count
        )
    }

    private static func trimmedTrailingWhitespace(_ characters: ArraySlice<Character>) -> ArraySlice<Character> {
        var end = characters.endIndex
        while end > characters.startIndex, characters[end - 1].isWhitespace {
            end -= 1
        }
        return characters[characters.startIndex ..< end]
    }
}
