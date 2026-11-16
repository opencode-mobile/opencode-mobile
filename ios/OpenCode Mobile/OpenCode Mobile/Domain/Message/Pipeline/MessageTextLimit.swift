import Foundation
import OpenCodeMarkdownCore

/// The part of a long message the timeline shows, and how many of its characters that is.
nonisolated struct MessageTextCut: Equatable, Sendable {
    /// The shown prefix; ends with "…" when a line was cut part way.
    let visibleText: String
    /// UTF-16 units of the message up to the end of `visibleText`, without the "…". Leading
    /// blank lines the shown part skips count as shown.
    let shownCharacters: Int
    /// Whether the last shown line was cut part way, so `visibleText` ends with the cut's "…".
    var endsMidLine = false

    /// `visibleText` as markdown detection and parsing read it: a cut line has the inline
    /// markers the cut left open closed before its "…", so it renders as the whole message does
    /// up to the cut (#1043). The plain shown text, Copy message, Select text and Full message
    /// keep `visibleText` and the whole text. The repair is the shared parser's, as on Android.
    var markdownSource: String {
        guard endsMidLine else { return visibleText }
        return OpenCodeMarkdownParser.shared.closeMarkersLeftOpen(prefix: String(visibleText.dropLast())) + "…"
    }
}

/// How much of a user's own message the timeline lays out (#1043).
///
/// Counted from the text alone, with no layout: a line break is one line, and every
/// `charactersPerLine` characters of a line are one more, so a line costs
/// `max(1, ceil(length / charactersPerLine))`. A message costing at most `tolerance` shows in
/// full; a longer one shows the prefix costing at most `limit`. Android has the same rule,
/// constants and test vectors.
///
/// Characters are UTF-16 units on both platforms, so the same text is cut at the same place and
/// shows the same counts. A UTF-16 unit is what `String.utf16` and a Kotlin `String` read one at
/// a time without decoding, so the pass stays cheap and stops early; grapheme clusters would
/// need segmenting every character read. A cut never ends inside a grapheme cluster (an emoji
/// sequence, a flag, a letter and its combining marks): only the cut line up to the cut is
/// segmented, with the platform's own rules (`Character` here, `\X` on Android).
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

    private static let lineFeed: UInt16 = 0x0A
    private static let carriageReturn: UInt16 = 0x0D

    /// The shown prefix of `text`, or nil when it shows in full.
    ///
    /// One pass that stops as soon as the message is known to be over `tolerance`: it reads at
    /// most about `tolerance * charactersPerLine` units plus `tolerance` line breaks, whatever
    /// the length of the text. Leading blank lines count toward that decision, but the shown
    /// part starts at the first line with content, so a paste that opens with many blank lines
    /// never shows an empty bubble; `MessageTextCut.shownCharacters` counts the skipped blank
    /// lines as shown.
    static func cut(_ text: String) -> MessageTextCut? {
        cut(units: text.utf16)
    }

    /// `cut(_:)` over the UTF-16 units of a text, read only as far as the cut needs.
    static func cut(units: some Sequence<UInt16>) -> MessageTextCut? {
        let text = UnitReader(units)
        guard let first = scan(text, start: 0, forced: false) else { return nil }
        guard let contentStart = first.contentLineStart ?? contentLineStart(text, from: first.read),
              contentStart > 0
        else { return first.cut }
        return scan(text, start: contentStart, forced: true)?.cut ?? first.cut
    }

    /// The units of a text read so far, reading more on demand.
    private final class UnitReader {
        private let next: () -> UInt16?
        private var isAtEnd = false
        private(set) var units: [UInt16] = []

        init(_ sequence: some Sequence<UInt16>) {
            var iterator = sequence.makeIterator()
            next = { iterator.next() }
            units.reserveCapacity(MessageTextLimit.tolerance * MessageTextLimit.charactersPerLine * 2)
        }

        /// The unit at `index`, or nil past the end of the text.
        subscript(index: Int) -> UInt16? {
            while units.count <= index, !isAtEnd {
                if let unit = next() {
                    units.append(unit)
                } else {
                    isAtEnd = true
                }
            }
            return index < units.count ? units[index] : nil
        }

        func string(_ range: Range<Int>) -> String {
            String(decoding: units[range], as: UTF16.self)
        }
    }

    private struct Scan {
        let cut: MessageTextCut
        /// Start of the first line with a non-blank character among those read, if any.
        let contentLineStart: Int?
        /// Where the pass stopped reading.
        let read: Int
    }

    /// Counts `text` from `start`. Nil when it fits `tolerance`, unless `forced`: then the part
    /// costing at most `limit` is returned even when the rest would fit.
    private static func scan(_ text: UnitReader, start: Int, forced: Bool) -> Scan? {
        var read = start
        var used = 0
        var lineStart = start
        var lineLength = 0
        var wholeEnd = start
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
                wholeEnd = read
            } else {
                overflowStart = lineStart
                overflowBudget = limit - used
            }
        }

        while let unit = text[read] {
            if unit == carriageReturn, text[read + 1] == lineFeed {
                // CRLF is one line break, as on Android.
                read += 1
                continue
            }
            if unit == lineFeed {
                let cost = lineCost(lineLength)
                markOverflowIfNeeded(cost: cost)
                used += cost
                if used > tolerance {
                    isOver = true
                    break
                }
                read += 1
                lineStart = read
                lineLength = 0
            } else {
                if contentLineStart == nil, !isBlank(unit) { contentLineStart = lineStart }
                read += 1
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

        var partialStart = start
        var partialEnd = start
        if let overflowStart, overflowBudget > 0 {
            partialStart = overflowStart
            let end = overflowStart + overflowBudget * charactersPerLine
            partialEnd = min(end, read)
            let nextIsSpace = end < read && isBlank(text.units[end])
            if !nextIsSpace {
                var space = partialEnd - 1
                while space >= partialStart, !isBlank(text.units[space]) {
                    space -= 1
                }
                if space - partialStart > minimumWordCut { partialEnd = space }
            }
            partialEnd = graphemeFloor(text, lineStart: partialStart, end: partialEnd)
            partialEnd = trimmedEnd(text, start: partialStart, end: partialEnd)
        }
        let cut: MessageTextCut
        if partialEnd <= partialStart {
            let visibleEnd = trimmedEnd(text, start: start, end: wholeEnd)
            cut = MessageTextCut(visibleText: text.string(start ..< visibleEnd), shownCharacters: visibleEnd)
        } else {
            cut = MessageTextCut(
                visibleText: text.string(start ..< partialEnd) + "…",
                shownCharacters: partialEnd,
                endsMidLine: true
            )
        }
        return Scan(cut: cut, contentLineStart: contentLineStart, read: read)
    }

    /// The last grapheme cluster boundary at or before `end` in the line starting at `lineStart`
    /// (a boundary, since a line break ends every cluster). Reads only from `lineStart` to one
    /// code point past `end`: whether `end` is a boundary depends on what comes before it and
    /// the code point at it.
    private static func graphemeFloor(_ text: UnitReader, lineStart: Int, end: Int) -> Int {
        guard end > lineStart, text[end] != nil else { return end }
        _ = text[end + 1]
        // A lone surrogate decodes to one U+FFFD, so offsets in the window stay unit offsets.
        let window = text.string(lineStart ..< min(text.units.count, end + 2))
        let target = end - lineStart
        var offset = 0
        var boundary = 0
        for character in window {
            offset += character.utf16.count
            if offset > target { break }
            boundary = offset
        }
        return lineStart + boundary
    }

    /// Start of the first line of `text` with a non-blank unit, looking from `from` for at most
    /// `maximumBlankSkip` units; nil if none.
    private static func contentLineStart(_ text: UnitReader, from: Int) -> Int? {
        var lineStart = from
        while lineStart > 0, text[lineStart - 1] != lineFeed {
            lineStart -= 1
        }
        var index = from
        let end = from + maximumBlankSkip
        while index < end, let unit = text[index] {
            if unit == lineFeed {
                lineStart = index + 1
            } else if !isBlank(unit) {
                return lineStart
            }
            index += 1
        }
        return nil
    }

    private static func trimmedEnd(_ text: UnitReader, start: Int, end: Int) -> Int {
        var trimmed = end
        while trimmed > start, isBlank(text.units[trimmed - 1]) {
            trimmed -= 1
        }
        return trimmed
    }

    /// Blank units, spelt out to match Android's `MessageTextLimit.isBlank` (Kotlin's
    /// `Char.isWhitespace()` set), so both platforms trim and skip the same characters.
    static func isBlank(_ unit: UInt16) -> Bool {
        switch unit {
        case 0x09 ... 0x0D, 0x1C ... 0x20, 0xA0, 0x1680, 0x2000 ... 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000:
            true
        default:
            false
        }
    }
}

nonisolated extension MessageTextLimit {
    /// The text markdown detection, parsing and highlighting read for a message: the shown part
    /// of a cut user message (`MessageTextCut.markdownSource`), so none of them ever sees the
    /// hidden rest (#1043); the whole text otherwise. A cut bubble then renders like an uncut one
    /// up to the cut.
    static func markdownSource(role: MessageRole, text: String) -> String {
        guard role == .user, let cut = cut(text) else { return text }
        return cut.markdownSource
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
