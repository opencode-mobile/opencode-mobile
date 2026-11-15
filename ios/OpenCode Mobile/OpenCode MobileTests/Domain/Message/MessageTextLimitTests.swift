import Foundation
import Testing
@testable import OpenCode_Mobile

/// Same vectors as Android `MessageTextLimitTest`.
@Suite struct MessageTextLimitTests {
    /// "line 1" … "line n", one per line.
    private static func lines(_ count: Int) -> String {
        (1 ... count).map { "line \($0)" }.joined(separator: "\n")
    }

    /// "abcdef abcdef …" cut to `count` characters: a word every 7.
    private static func words(_ count: Int) -> String {
        String(String(repeating: "abcdef ", count: count / 7 + 1).prefix(count))
    }

    @Test func constants() {
        #expect(MessageTextLimit.charactersPerLine == 40)
        #expect(MessageTextLimit.limit == 25)
        #expect(MessageTextLimit.tolerance == 30)
    }

    @Test func empty_showsInFull() {
        #expect(MessageTextLimit.cut("") == nil)
    }

    @Test func short_showsInFull() {
        #expect(MessageTextLimit.cut("Hello") == nil)
    }

    @Test func exactlyTheLimit_showsInFull() {
        #expect(MessageTextLimit.cut(Self.lines(25)) == nil)
    }

    @Test(arguments: [26, 27, 28, 29, 30])
    func withinTheTolerance_showsInFull(lineCount: Int) {
        #expect(MessageTextLimit.cut(Self.lines(lineCount)) == nil)
    }

    @Test func oneLinePastTheTolerance_cutsToTheLimit() {
        let shown = Self.lines(25)
        #expect(
            MessageTextLimit.cut(Self.lines(31))
                == MessageTextCut(visibleText: shown, shownCharacters: shown.count)
        )
    }

    @Test func overByLineBreaks_keepsTheFirstWholeLines() {
        let shown = Self.lines(25)
        #expect(
            MessageTextLimit.cut(Self.lines(100))
                == MessageTextCut(visibleText: shown, shownCharacters: shown.count)
        )
    }

    @Test func blankLinesCountAsLines() {
        let text = "first" + String(repeating: "\n", count: 40) + "last"
        #expect(MessageTextLimit.cut(text) == MessageTextCut(visibleText: "first", shownCharacters: 5))
    }

    @Test func unbrokenLineAtTheTolerance_showsInFull() {
        #expect(MessageTextLimit.cut(String(repeating: "a", count: 1_200)) == nil)
    }

    @Test func unbrokenLinePastTheTolerance_isCutAtTheLimit() {
        let expected = MessageTextCut(visibleText: String(repeating: "a", count: 1_000) + "…", shownCharacters: 1_000)
        #expect(MessageTextLimit.cut(String(repeating: "a", count: 1_201)) == expected)
        #expect(MessageTextLimit.cut(String(repeating: "a", count: 5_000)) == expected)
    }

    @Test func mixed_keepsWholeLinesThenCutsTheLongLineAtAWord() {
        // 20 short lines (cost 20) and a 600-character line (cost 15): 35 > 30.
        let text = Self.lines(20) + "\n" + Self.words(600)
        // 5 lines left = 200 characters; the last word that ends inside them ends at 195.
        let partial = Self.words(195)
        let cut = MessageTextLimit.cut(text)
        #expect(cut?.visibleText == Self.lines(20) + "\n" + partial + "…")
        #expect(cut?.shownCharacters == Self.lines(20).count + 1 + 195)
        #expect(partial.hasSuffix("abcdef"))
    }

    @Test func wordEndingExactlyAtTheCut_isKeptWhole() {
        // Character 1,000 is a space, so the first 1,000 characters end on a whole word.
        let cut = MessageTextLimit.cut(Self.words(2_000))
        #expect(cut == MessageTextCut(visibleText: Self.words(1_000) + "…", shownCharacters: 1_000))
    }

    @Test func wordBoundaryNearTheLineStart_isIgnored() {
        let text = String(repeating: "a", count: 10) + " " + String(repeating: "b", count: 2_000)
        let kept = String(repeating: "a", count: 10) + " " + String(repeating: "b", count: 989)
        #expect(MessageTextLimit.cut(text) == MessageTextCut(visibleText: kept + "…", shownCharacters: 1_000))
    }

    @Test func crlfLineBreaks_countLikeLineFeeds() {
        // Same vector as Android: "\r\n" is one line break, not a character of the line.
        let line = String(repeating: "a", count: 40)
        #expect(MessageTextLimit.cut(Array(repeating: line, count: 30).joined(separator: "\r\n")) == nil)

        let cut = MessageTextLimit.cut(Array(repeating: line, count: 31).joined(separator: "\r\n"))
        #expect(cut?.visibleText == Array(repeating: line, count: 25).joined(separator: "\r\n"))
    }

    @Test(arguments: ["", "   ", "\n\n", "plain", "  lead", "trail \n", "\t both \r\n", "é\u{2028}", "\u{00A0}x\u{3000}"])
    func trimmingWhitespaceAndNewlines_matchesFoundation(text: String) {
        #expect(text.trimmingWhitespaceAndNewlines == text.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    @Test func multiMegabyteLog_stopsReadingEarly() {
        // About 3.2 MB of 35-character log lines.
        let line = "2026-01-01T00:00:00Z INFO worker ok"
        let text = Array(repeating: line, count: 90_000).joined(separator: "\n")
        let counted = CountingCharacters(text)

        let cut = MessageTextLimit.cut(counted)

        let shown = Array(repeating: line, count: 25).joined(separator: "\n")
        #expect(cut == MessageTextCut(visibleText: shown, shownCharacters: shown.count))
        #expect(text.utf8.count > 3_000_000)
        #expect(counted.counter.read <= Self.maximumRead)
    }

    @Test func multiMegabyteSingleLine_stopsReadingEarly() {
        let counted = CountingCharacters(String(repeating: "x", count: 3_000_000))

        let cut = MessageTextLimit.cut(counted)

        #expect(cut?.shownCharacters == 1_000)
        #expect(counted.counter.read == 1_201)
    }

    /// At most `tolerance` lines' worth of characters plus their line breaks.
    private static let maximumRead =
        MessageTextLimit.tolerance * MessageTextLimit.charactersPerLine + MessageTextLimit.tolerance + 1
}

/// Characters of a string, counting how many were read.
private struct CountingCharacters: Sequence {
    final class Counter {
        var read = 0
    }

    let text: String
    let counter = Counter()

    init(_ text: String) {
        self.text = text
    }

    func makeIterator() -> AnyIterator<Character> {
        var iterator = text.makeIterator()
        let counter = counter
        return AnyIterator {
            guard let next = iterator.next() else { return nil }
            counter.read += 1
            return next
        }
    }
}
