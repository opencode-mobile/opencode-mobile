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

    @Test func manyLeadingBlankLines_showTheContentAfterThem() {
        // 40 blank lines alone are over the tolerance; the shown part starts at the content.
        let shown = Self.lines(25)
        #expect(
            MessageTextLimit.cut(String(repeating: "\n", count: 40) + Self.lines(100))
                == MessageTextCut(visibleText: shown, shownCharacters: 40 + shown.count)
        )
        #expect(
            MessageTextLimit.cut(String(repeating: "\n", count: 40) + "hello")
                == MessageTextCut(visibleText: "hello", shownCharacters: 45)
        )
    }

    @Test func leadingWhitespaceOnlyLines_areSkippedButIndentationIsKept() {
        let text = String(repeating: "  \n", count: 40) + "    indented\nmore"
        #expect(MessageTextLimit.cut(text) == MessageTextCut(visibleText: "    indented\nmore", shownCharacters: text.count))
    }

    @Test func fewLeadingBlankLines_stillStartTheCutAtTheContent() {
        let shown = Self.lines(25)
        #expect(MessageTextLimit.cut("\n\nhello") == nil)
        #expect(
            MessageTextLimit.cut("\n\n" + Self.lines(100))
                == MessageTextCut(visibleText: shown, shownCharacters: 2 + shown.count)
        )
    }

    @Test func onlyBlankLines_showNothing() {
        #expect(MessageTextLimit.cut(String(repeating: "\n", count: 100)) == MessageTextCut(visibleText: "", shownCharacters: 0))
    }

    @Test func multiMegabyteBlankPaste_stopsLookingForContent() {
        let counted = CountingUnits(String(repeating: "\n", count: 3_000_000) + "late")

        #expect(MessageTextLimit.cut(units: counted) == MessageTextCut(visibleText: "", shownCharacters: 0))
        #expect(counted.counter.read <= Self.maximumRead + MessageTextLimit.maximumBlankSkip + 1)
    }

    @Test func unbrokenLineAtTheTolerance_showsInFull() {
        #expect(MessageTextLimit.cut(String(repeating: "a", count: 1_200)) == nil)
    }

    @Test func unbrokenLinePastTheTolerance_isCutAtTheLimit() {
        let expected = MessageTextCut(
            visibleText: String(repeating: "a", count: 1_000) + "…",
            shownCharacters: 1_000,
            endsMidLine: true
        )
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
        #expect(cut == MessageTextCut(visibleText: Self.words(1_000) + "…", shownCharacters: 1_000, endsMidLine: true))
    }

    @Test func wordBoundaryNearTheLineStart_isIgnored() {
        let text = String(repeating: "a", count: 10) + " " + String(repeating: "b", count: 2_000)
        let kept = String(repeating: "a", count: 10) + " " + String(repeating: "b", count: 989)
        #expect(MessageTextLimit.cut(text) == MessageTextCut(visibleText: kept + "…", shownCharacters: 1_000, endsMidLine: true))
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
        let counted = CountingUnits(text)

        let cut = MessageTextLimit.cut(units: counted)

        let shown = Array(repeating: line, count: 25).joined(separator: "\n")
        #expect(cut == MessageTextCut(visibleText: shown, shownCharacters: shown.count))
        #expect(text.utf8.count > 3_000_000)
        #expect(counted.counter.read <= Self.maximumRead)
    }

    @Test func multiMegabyteSingleLine_stopsReadingEarly() {
        let counted = CountingUnits(String(repeating: "x", count: 3_000_000))

        let cut = MessageTextLimit.cut(units: counted)

        #expect(cut?.shownCharacters == 1_000)
        #expect(counted.counter.read == 1_201)
    }

    // Shared vectors with Android `MessageTextLimitTest`: the cut, the shown count and the total
    // count are the same on both platforms (UTF-16 units), and no cut ends inside a grapheme
    // cluster. Both platforms segment these sequences alike; their grapheme rules can differ for
    // rarer ones (an Indic conjunct, say), where the cut may then land one cluster apart.

    /// Expects the shown part of `text` and the two counts its row shows.
    private static func expectCut(
        _ text: String,
        _ visibleText: String,
        shown: Int,
        total: Int,
        sourceLocation: SourceLocation = #_sourceLocation
    ) {
        let cut = MessageTextLimit.cut(text)
        #expect(cut?.visibleText == visibleText, sourceLocation: sourceLocation)
        #expect(cut?.shownCharacters == shown, sourceLocation: sourceLocation)
        #expect(text.utf16.count == total, sourceLocation: sourceLocation)
    }

    private static func repeated(_ text: String, _ count: Int) -> String {
        String(repeating: text, count: count)
    }

    @Test func emojiOnlyLines_costTheirUtf16Units() {
        let line = Self.repeated("😀", 10)
        Self.expectCut(
            Array(repeating: line, count: 40).joined(separator: "\n"),
            Array(repeating: line, count: 25).joined(separator: "\n"),
            shown: 524,
            total: 839
        )
    }

    @Test func emojiOnlyLine_isCutAtTheLimitOfUnits() {
        Self.expectCut(Self.repeated("😀", 700), Self.repeated("😀", 500) + "…", shown: 1_000, total: 1_400)
        // The 1,000th unit is the first half of an emoji: the cut keeps the emoji before it.
        Self.expectCut("a" + Self.repeated("😀", 700), "a" + Self.repeated("😀", 499) + "…", shown: 999, total: 1_401)
    }

    @Test func mixedTextAndEmoji_cutsAtAWord() {
        // 20 short lines (cost 20) and an 800-unit line (cost 20): 5 lines left = 200 units.
        let text = Self.lines(20) + "\n" + Self.repeated("word 😀 ", 100)
        Self.expectCut(text, Self.lines(20) + "\n" + Self.repeated("word 😀 ", 24) + "word 😀…", shown: 350, total: 951)
    }

    @Test func zwjFamilyAtTheCut_isKeptWhole() {
        let family = "👨‍👩‍👧‍👦"
        #expect(family.utf16.count == 11)
        Self.expectCut(
            Self.repeated("a", 995) + family + Self.repeated("b", 300),
            Self.repeated("a", 995) + "…",
            shown: 995,
            total: 1_306
        )
    }

    @Test func flagsAtTheCut_areKeptWhole() {
        // The cut falls between the two regional indicators of the first flag.
        Self.expectCut(
            Self.repeated("a", 998) + "🇺🇸🇫🇷" + Self.repeated("b", 300),
            Self.repeated("a", 998) + "…",
            shown: 998,
            total: 1_306
        )
        // The cut falls between the two flags.
        Self.expectCut(
            Self.repeated("a", 996) + "🇺🇸🇫🇷" + Self.repeated("b", 300),
            Self.repeated("a", 996) + "🇺🇸…",
            shown: 1_000,
            total: 1_304
        )
    }

    @Test func skinToneAndVariationSelectorAtTheCut_stayWithTheirEmoji() {
        Self.expectCut(
            Self.repeated("a", 998) + "👍🏻" + Self.repeated("b", 300),
            Self.repeated("a", 998) + "…",
            shown: 998,
            total: 1_302
        )
        Self.expectCut(
            Self.repeated("a", 999) + "❤\u{FE0F}" + Self.repeated("b", 300),
            Self.repeated("a", 999) + "…",
            shown: 999,
            total: 1_301
        )
    }

    @Test func combiningMarkAtTheCut_staysWithItsLetter() {
        Self.expectCut(
            Self.repeated("a", 999) + "e\u{301}" + Self.repeated("b", 300),
            Self.repeated("a", 999) + "…",
            shown: 999,
            total: 1_301
        )
    }

    @Test func cjkText_countsOneUnitPerCharacter() {
        Self.expectCut(Self.repeated("中文", 700), Self.repeated("中文", 500) + "…", shown: 1_000, total: 1_400)
        // 50-character lines cost 2: twelve whole lines, then 40 characters of the 13th.
        let line = Self.repeated("中", 50)
        Self.expectCut(
            Array(repeating: line, count: 40).joined(separator: "\n"),
            Array(repeating: line, count: 12).joined(separator: "\n") + "\n" + Self.repeated("中", 40) + "…",
            shown: 652,
            total: 2_039
        )
    }

    /// At most `tolerance` lines' worth of characters plus their line breaks.
    private static let maximumRead =
        MessageTextLimit.tolerance * MessageTextLimit.charactersPerLine + MessageTextLimit.tolerance + 1
}

/// UTF-16 units of a string, counting how many were read.
private struct CountingUnits: Sequence {
    final class Counter {
        var read = 0
    }

    let text: String
    let counter = Counter()

    init(_ text: String) {
        self.text = text
    }

    func makeIterator() -> AnyIterator<UInt16> {
        var iterator = text.utf16.makeIterator()
        let counter = counter
        return AnyIterator {
            guard let next = iterator.next() else { return nil }
            counter.read += 1
            return next
        }
    }
}
